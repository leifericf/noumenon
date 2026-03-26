(ns noumenon.analyze
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d])
  (:import [java.security MessageDigest]
           [java.util Date]))

;; --- Constants ---

(def ^:private max-string-length 4096)

(def ^:private valid-complexity
  #{:trivial :simple :moderate :complex :very-complex})

(def ^:private valid-layer
  #{:core :subsystem :driver :api :util})

(def ^:private valid-code-kind
  #{:function :macro :protocol :multimethod :record :type
    :struct :enum :typedef :global :include :namespace})

;; --- Prompt ---

(defn load-prompt-template
  "Load the analyze-file prompt template from classpath. Returns the template map."
  []
  (-> (io/resource "prompts/analyze-file.edn") slurp edn/read-string))

(defn prompt-hash
  "SHA-256 hash of the prompt template string, truncated to 16 hex chars."
  [template-str]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes template-str "UTF-8"))]
    (->> bytes (map #(format "%02x" %)) (apply str) (take 16) (apply str))))

(defn render-prompt
  "Substitute template variables into the prompt template string."
  [template {:keys [file-path lang line-count content repo-name]}]
  (-> template
      (str/replace "{{file-path}}" (or file-path ""))
      (str/replace "{{lang}}" (name (or lang :unknown)))
      (str/replace "{{lang-name}}" (name (or lang :unknown)))
      (str/replace "{{line-count}}" (str (or line-count 0)))
      (str/replace "{{content}}" (or content ""))
      (str/replace "{{repo-name}}" (or repo-name ""))))

;; --- Defensive EDN parsing ---

(defn- clamp
  "Truncate string to max-string-length if needed."
  [s]
  (if (and (string? s) (> (count s) max-string-length))
    (subs s 0 max-string-length)
    s))

(defn- strip-markdown-fences
  "Remove markdown code fences from LLM output."
  [s]
  (-> s
      str/trim
      (str/replace #"^```\w*\n?" "")
      (str/replace #"\n?```$" "")
      str/trim))

(defn- validate-segment
  "Validate a segment map. Returns cleaned segment or nil."
  [{:keys [name kind line-start line-end]}]
  (when (and (string? name) (not (str/blank? name)))
    (cond-> {:name (clamp name)}
      (valid-code-kind kind)     (assoc :kind kind)
      (integer? line-start)      (assoc :line-start line-start)
      (integer? line-end)        (assoc :line-end line-end))))

(defn parse-llm-response
  "Parse LLM stdout as EDN. Strips markdown fences on failure and retries once.
   Returns parsed map with validated/clamped values, or nil."
  [raw]
  (when-not (str/blank? raw)
    (let [try-parse (fn [s]
                      (try
                        (let [result (edn/read-string s)]
                          (when (map? result) result))
                        (catch Exception _ nil)))
          parsed    (or (try-parse (str/trim raw))
                        (try-parse (strip-markdown-fences raw)))]
      (when parsed
        (let [{:keys [summary purpose tags complexity patterns layer
                      confidence segments]} parsed
              known-keys (cond-> {}
                           (string? summary)
                           (assoc :summary (clamp summary))

                           (string? purpose)
                           (assoc :purpose (clamp purpose))

                           (and (coll? tags) (seq tags))
                           (assoc :tags (vec (filter keyword? tags)))

                           (valid-complexity complexity)
                           (assoc :complexity complexity)

                           (and (coll? patterns) (seq patterns))
                           (assoc :patterns (vec (filter keyword? patterns)))

                           (valid-layer layer)
                           (assoc :layer layer)

                           (and (number? confidence)
                                (<= 0.0 confidence 1.0))
                           (assoc :confidence (double confidence))

                           (and (coll? segments) (seq segments))
                           (assoc :segments (->> segments
                                                 (keep validate-segment)
                                                 (take 20)
                                                 vec)))]
          (when (seq known-keys)
            known-keys))))))

;; --- Tx-data building ---

(defn analysis->tx-data
  "Convert a parsed analysis map into Datomic tx-data for a file.
   `file-path` is the repo-relative path. Returns tx-data vector (may be empty)."
  [file-path analysis {:keys [model-version prompt-hash-val analyzer]}]
  (when (and analysis (seq analysis))
    (let [{:keys [summary purpose tags complexity patterns layer
                  confidence segments]} analysis
          file-ref [:file/path file-path]
          file-tx  (cond-> {:file/path file-path}
                     summary    (assoc :sem/summary summary)
                     purpose    (assoc :sem/purpose purpose)
                     (seq tags) (assoc :sem/tags tags)
                     complexity (assoc :sem/complexity complexity)
                     (seq patterns) (assoc :sem/patterns patterns)
                     layer      (assoc :arch/layer layer)
                     confidence (assoc :prov/confidence confidence))
          seg-txs  (when (seq segments)
                     (mapv (fn [{:keys [name kind line-start line-end]}]
                             (cond-> {:code/name name
                                      :code/file file-ref}
                               kind       (assoc :code/kind kind)
                               line-start (assoc :code/line-start line-start)
                               line-end   (assoc :code/line-end line-end)))
                           segments))
          prov-tx  {:db/id               "datomic.tx"
                    :tx/op               :analyze
                    :tx/source           :llm
                    :tx/model            (or model-version "unknown")
                    :tx/analyzer         (or analyzer "noumenon.analyze/0.1.0")
                    :prov/model-version  (or model-version "unknown")
                    :prov/prompt-hash    (or prompt-hash-val "")
                    :prov/analyzed-at    (Date.)}]
      (into [file-tx prov-tx] seg-txs))))

;; --- LLM invocation ---

(defn invoke-claude-cli
  "Invoke Claude Code CLI with the given prompt. Returns stdout string.
   Throws on process failure."
  [prompt]
  (let [{:keys [exit out err]} (shell/sh "claude" "--print" "-p" prompt)]
    (when (not= 0 exit)
      (throw (ex-info (str "Claude CLI failed: " (str/trim (or err "")))
                      {:exit exit})))
    out))

;; --- File content ---

(defn git-show
  "Read file content from HEAD via `git show HEAD:<path>`."
  [repo-path file-path]
  (let [{:keys [exit out err]} (shell/sh "git" "-C" (str repo-path)
                                         "show" (str "HEAD:" file-path))]
    (when (not= 0 exit)
      (throw (ex-info (str "git show failed: " (str/trim (or err "")))
                      {:exit exit :file-path file-path})))
    out))

;; --- Skip logic ---

(defn files-needing-analysis
  "Return file entities (as maps with :file/path and :file/lang) that have
   a recognized language but no :sem/summary yet."
  [db]
  (->> (d/q '[:find ?path ?lang
              :where
              [?e :file/path ?path]
              [?e :file/lang ?lang]
              (not [?e :sem/summary _])]
            db)
       (mapv (fn [[path lang]] {:file/path path :file/lang lang}))
       (sort-by :file/path)))

;; --- Orchestration ---

(defn analyze-file!
  "Analyze a single file. Returns :ok, :skipped, or :error with details."
  [conn repo-path file-map prompt-template prompt-hash-val invoke-llm]
  (let [{:keys [file/path file/lang]} file-map
        repo-name (last (str/split (str repo-path) #"/"))]
    (try
      (let [content  (git-show repo-path path)
            content  (if (> (count content) (* 100 1000))
                       (do (binding [*out* *err*]
                             (println (str "  Warning: truncating " path
                                           " (" (count content) " chars)")))
                           (subs content 0 (* 100 1000)))
                       content)
            prompt   (render-prompt prompt-template
                                    {:file-path  path
                                     :lang       lang
                                     :line-count (count (str/split-lines content))
                                     :content    content
                                     :repo-name  repo-name})
            raw      (invoke-llm prompt)
            analysis (parse-llm-response raw)]
        (if analysis
          (let [tx-data (analysis->tx-data path analysis
                                           {:model-version  "claude-sonnet-4-20250514"
                                            :prompt-hash-val prompt-hash-val
                                            :analyzer       "noumenon.analyze/0.1.0"})]
            (d/transact conn {:tx-data tx-data})
            :ok)
          (do (binding [*out* *err*]
                (println (str "  Warning: unparseable response for " path ", skipping")))
              :parse-error)))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "  Error analyzing " path ": " (.getMessage e))))
        :error))))

(defn analyze-repo!
  "Analyze all source files in repo-path that need analysis.
   `invoke-llm` is a function (prompt -> string) for testability.
   Returns summary map."
  [conn repo-path invoke-llm]
  (let [db             (d/db conn)
        files          (files-needing-analysis db)
        total          (count files)
        prompt-map     (load-prompt-template)
        template       (:template prompt-map)
        p-hash         (prompt-hash template)
        start-ms       (System/currentTimeMillis)]
    (if (zero? total)
      (do (binding [*out* *err*]
            (println "All files already analyzed, nothing to do."))
          {:files-analyzed 0 :files-skipped 0 :files-errored 0 :elapsed-ms 0})
      (do
        (binding [*out* *err*]
          (println (str "Analyzing " total " files...")))
        (let [results (reduce
                       (fn [acc [idx file-map]]
                         (binding [*out* *err*]
                           (print (str "  [" (inc idx) "/" total "] "
                                       (:file/path file-map) " ... "))
                           (flush))
                         (let [start  (System/currentTimeMillis)
                               result (analyze-file! conn repo-path file-map
                                                     template p-hash invoke-llm)
                               dur    (- (System/currentTimeMillis) start)]
                           (binding [*out* *err*]
                             (println (str (format "%.1f" (/ dur 1000.0)) "s "
                                           (name result))))
                           (update acc result (fnil inc 0))))
                       {:ok 0 :parse-error 0 :error 0}
                       (map-indexed vector files))
              elapsed (- (System/currentTimeMillis) start-ms)]
          (binding [*out* *err*]
            (println (str "Done. " (:ok results 0) " analyzed, "
                          (:parse-error results 0) " parse errors, "
                          (:error results 0) " errors. "
                          "(" elapsed " ms)")))
          {:files-analyzed (:ok results 0)
           :files-skipped  0
           :files-errored  (+ (:parse-error results 0) (:error results 0))
           :elapsed-ms     elapsed})))))
