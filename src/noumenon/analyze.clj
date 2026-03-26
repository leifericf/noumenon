(ns noumenon.analyze
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.llm :as llm]
            [noumenon.util :as util :refer [escape-template-vars log! sha256-hex]])
  (:import [java.util Date]))

;; --- Constants ---

(def ^:private max-string-length 4096)
(def ^:private max-file-content-chars (* 100 1000))
(def ^:private max-segments 20)

(def ^:private valid-complexity
  #{:trivial :simple :moderate :complex :very-complex})

(def ^:private valid-layer
  #{:core :subsystem :driver :api :util})

(def ^:private valid-code-kind
  #{:function :method :class :interface :trait :protocol
    :macro :multimethod :record :type :struct :enum :union
    :typedef :constant :property :module :namespace
    :global :include})

(def ^:private valid-visibility
  #{:public :private :protected :internal :package})

(def ^:private valid-segment-complexity
  #{:trivial :simple :moderate :complex})

(def ^:private valid-smells
  #{:deep-nesting :too-many-params :long-method :god-function
    :feature-envy :magic-numbers :mutation-heavy :global-state
    :duplication :poor-naming :dead-code :complex-conditional})

(def ^:private valid-safety-concerns
  #{:sql-injection :xss :race-condition :unsafe-cast
    :path-traversal :command-injection :buffer-overflow
    :deserialization :hardcoded-secret :insecure-random
    :unvalidated-redirect})

(def ^:private valid-ai-likelihood
  #{:likely-ai :possibly-ai :likely-human :unknown})

(def ^:private valid-error-handling
  #{:none :basic :robust})

(def ^:private valid-category
  #{:backend :frontend :infrastructure :configuration
    :data :testing :documentation :tooling :shared})

;; --- Prompt ---

(defn load-prompt-template
  "Load the analyze-file prompt template from classpath. Returns the template map."
  []
  (-> (io/resource "prompts/analyze-file.edn") slurp edn/read-string))

(defn prompt-hash
  "SHA-256 hash of the prompt template string, truncated to 16 hex chars."
  [template-str]
  (subs (sha256-hex template-str) 0 16))

(defn render-prompt
  "Substitute template variables into the prompt template string.
   Content is escaped and wrapped in delimiters to prevent prompt injection."
  [template {:keys [file-path lang line-count content repo-name]}]
  (let [safe-content (str "<file-content>\n"
                          (escape-template-vars content)
                          "\n</file-content>")]
    (-> template
        (str/replace "{{file-path}}" (or file-path ""))
        (str/replace "{{lang}}" (name (or lang :unknown)))
        (str/replace "{{lang-name}}" (name (or lang :unknown)))
        (str/replace "{{line-count}}" (str (or line-count 0)))
        (str/replace "{{content}}" safe-content)
        (str/replace "{{repo-name}}" (or repo-name "")))))

;; --- Defensive EDN parsing ---

(defn- clamp
  "Truncate string to max-string-length if needed."
  [s]
  (cond-> s (string? s) (util/truncate max-string-length)))

(defn strip-markdown-fences
  "Remove markdown code fences from LLM output."
  [s]
  (-> s
      str/trim
      (str/replace #"^```\w*\n?" "")
      (str/replace #"\n?```$" "")
      str/trim))

(defn- non-blank? [s] (and (string? s) (not (str/blank? s))))

(defn- validate-segment
  "Validate a segment map. Returns cleaned segment or nil."
  [{:keys [name kind line-start line-end
           args returns visibility docstring deprecated
           complexity smells purpose safety-concerns error-handling
           call-names pure ai-likelihood]}]
  (when (non-blank? name)
    (cond-> {:name (clamp name)}
      (valid-code-kind kind)                               (assoc :kind kind)
      (integer? line-start)                                (assoc :line-start line-start)
      (integer? line-end)                                  (assoc :line-end line-end)
      (non-blank? args)                                    (assoc :args (clamp args))
      (non-blank? returns)                                 (assoc :returns (clamp returns))
      (valid-visibility visibility)                        (assoc :visibility visibility)
      (non-blank? docstring)                               (assoc :docstring (clamp docstring))
      (true? deprecated)                                   (assoc :deprecated true)
      (valid-segment-complexity complexity)                (assoc :complexity complexity)
      (seq (filter valid-smells smells))                   (assoc :smells (vec (filter valid-smells smells)))
      (non-blank? purpose)                                 (assoc :purpose (clamp purpose))
      (seq (filter valid-safety-concerns safety-concerns)) (assoc :safety-concerns (vec (filter valid-safety-concerns safety-concerns)))
      (valid-error-handling error-handling)                 (assoc :error-handling error-handling)
      (seq (filter non-blank? call-names))                 (assoc :call-names (vec (filter non-blank? call-names)))
      (true? pure)                                         (assoc :pure true)
      (valid-ai-likelihood ai-likelihood)                  (assoc :ai-likelihood ai-likelihood))))

(def ^:private analysis-sanitizers
  {:summary      #(when (string? %) (clamp %))
   :purpose      #(when (string? %) (clamp %))
   :tags         #(when (and (coll? %) (seq %)) (vec (filter keyword? %)))
   :complexity   #(when (valid-complexity %) %)
   :patterns     #(when (and (coll? %) (seq %)) (vec (filter keyword? %)))
   :layer        #(when (valid-layer %) %)
   :confidence   #(when (and (number? %) (<= 0.0 % 1.0)) (double %))
   :category     #(when (valid-category %) %)
   :dependencies (fn [v] (when (and (coll? v) (seq v))
                           (vec (filter non-blank? v))))
   :segments     #(when (and (coll? %) (seq %))
                    (->> % (keep validate-segment) (take max-segments) vec))})

(defn- sanitize-analysis
  [parsed]
  (->> analysis-sanitizers
       (reduce-kv (fn [acc k sanitize]
                    (if-let [v (sanitize (get parsed k))]
                      (assoc acc k v)
                      acc))
                  {})))

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
        (let [analysis (sanitize-analysis parsed)]
          (when (seq analysis)
            analysis))))))

;; --- Tx-data building ---

(def ^:private segment-key-mapping
  "Maps validated segment keys to Datomic :code/* attribute keys."
  {:name :code/name :kind :code/kind :line-start :code/line-start :line-end :code/line-end
   :args :code/args :returns :code/returns :visibility :code/visibility
   :docstring :code/docstring :complexity :code/complexity :purpose :code/purpose
   :error-handling :code/error-handling :ai-likelihood :code/ai-likelihood})

(defn- segment->tx-data
  "Convert a validated segment map to a Datomic tx-data map."
  [file-ref seg]
  (let [base (-> (reduce-kv (fn [acc k v] (if-let [dk (segment-key-mapping k)] (assoc acc dk v) acc))
                            {:code/file file-ref}
                            seg))]
    (cond-> base
      (:deprecated seg)            (assoc :code/deprecated? true)
      (seq (:smells seg))          (assoc :code/smells (:smells seg))
      (seq (:safety-concerns seg)) (assoc :code/safety-concerns (:safety-concerns seg))
      (seq (:call-names seg))      (assoc :code/call-names (:call-names seg))
      (:pure seg)                  (assoc :code/pure? true))))

(defn analysis->tx-data
  "Convert a parsed analysis map into Datomic tx-data for a file.
   `file-path` is the repo-relative path. Returns tx-data vector (may be empty)."
  [file-path analysis {:keys [model-version prompt-hash-val analyzer]}]
  (when (and analysis (seq analysis))
    (let [{:keys [summary purpose tags complexity patterns layer
                  category confidence dependencies segments]} analysis
          file-ref [:file/path file-path]
          file-tx  (cond-> {:file/path file-path}
                     summary          (assoc :sem/summary summary)
                     purpose          (assoc :sem/purpose purpose)
                     (seq tags)       (assoc :sem/tags tags)
                     complexity       (assoc :sem/complexity complexity)
                     (seq patterns)   (assoc :sem/patterns patterns)
                     layer            (assoc :arch/layer layer)
                     category         (assoc :sem/category category)
                     confidence       (assoc :prov/confidence confidence)
                     (seq dependencies) (assoc :sem/dependencies dependencies))
          seg-txs  (when (seq segments)
                     (->> segments
                          (reduce (fn [acc {:keys [name line-start] :as seg}]
                                    (let [unique-name (if (contains? (:seen acc) name)
                                                        (str name ":L" (or line-start
                                                                           (count (:seen acc))))
                                                        name)]
                                      (if (contains? (:seen acc) unique-name)
                                        acc
                                        (-> acc
                                            (update :seen conj name unique-name)
                                            (update :txs conj (assoc seg :name unique-name))))))
                                  {:seen #{} :txs []})
                          :txs
                          (mapv (partial segment->tx-data file-ref))))
          prov-tx  {:db/id               "datomic.tx"
                    :tx/op               :analyze
                    :tx/source           :llm
                    :tx/model            (or model-version "unknown")
                    :tx/analyzer         (or analyzer "noumenon.analyze/0.1.0")
                    :prov/model-version  (or model-version "unknown")
                    :prov/prompt-hash    (or prompt-hash-val "")
                    :prov/analyzed-at    (Date.)}]
      (into [file-tx prov-tx] seg-txs))))

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

(defn repo-name
  "Extract the last path component from a repo path, stripping trailing slashes."
  [repo-path]
  (-> (str repo-path) (str/replace #"/+$" "") (str/split #"/") last))

(defn- truncate-content
  "Truncate content to max-file-content-chars. Returns [content truncated?]."
  [content]
  (if (> (count content) max-file-content-chars)
    [(subs content 0 max-file-content-chars) true]
    [content false]))

(defn analyze-file!
  "Analyze a single file. Returns {:status :ok/:parse-error/:error, :usage map-or-nil}.
   Retries once on parse errors (unparseable LLM response)."
  [conn repo-path file-map {:keys [prompt-template prompt-hash-val invoke-llm]}]
  (let [{:keys [file/path file/lang]} file-map
        repo-name  (repo-name repo-path)
        usage-atom (atom nil)]
    (try
      (let [raw-content          (git-show repo-path path)
            [content truncated?] (truncate-content raw-content)
            prompt   (render-prompt prompt-template
                                    {:file-path  path
                                     :lang       lang
                                     :line-count (count (str/split-lines content))
                                     :content    content
                                     :repo-name  repo-name})
            try-invoke (fn []
                         (let [{:keys [text usage resolved-model]} (invoke-llm prompt)]
                           (reset! usage-atom usage)
                           {:text text :usage usage :resolved-model resolved-model
                            :analysis (parse-llm-response text) :truncated? truncated?}))
            result   (try-invoke)
            result   (if (:analysis result)
                       result
                       (do (log! "  Retrying (unparseable response)...")
                           (let [r2 (try-invoke)]
                             (update r2 :usage #(llm/sum-usage (:usage result) %)))))]
        (if-let [analysis (:analysis result)]
          (let [tx-data (analysis->tx-data path analysis
                                           {:model-version   (or (:resolved-model result) "unknown")
                                            :prompt-hash-val prompt-hash-val
                                            :analyzer        "noumenon.analyze/0.1.0"})]
            (d/transact conn {:tx-data tx-data})
            {:status :ok :usage (:usage result) :truncated? truncated?})
          (do (log! (str "  Warning: unparseable response for " path ", skipping"))
              {:status :parse-error :usage (:usage result) :truncated? truncated?})))
      (catch Exception e
        (log! (str "  Error analyzing " path ": " (.getMessage e)))
        {:status :error :usage @usage-atom}))))

(defn- format-eta
  "Format estimated time remaining as a human-readable string."
  [eta-ms]
  (let [secs (/ eta-ms 1000.0)]
    (cond
      (< secs 60)   (str (Math/round secs) "s")
      (< secs 3600) (str (Math/round (/ secs 60.0)) "m")
      :else         (format "%.1fh" (/ secs 3600.0)))))

(defn- analyze-one-file!
  "Analyze one file with progress logging and ETA. Returns updated accumulator."
  [conn repo-path analysis-opts total acc [idx file-map]]
  (let [elapsed-so-far (:elapsed-ms acc 0)
        eta-str        (when (and (> idx 1) (pos? elapsed-so-far))
                         (let [avg-ms    (/ elapsed-so-far (double idx))
                               remaining (- total idx)]
                           (str " eta " (format-eta (* avg-ms remaining)))))]
    (binding [*out* *err*]
      (print (str "  [" (inc idx) "/" total (or eta-str "") "] "
                  (:file/path file-map) " ... "))
      (flush)))
  (let [start  (System/currentTimeMillis)
        result (analyze-file! conn repo-path file-map analysis-opts)
        {:keys [status usage truncated?]} result
        dur    (- (System/currentTimeMillis) start)]
    (log! (str (format "%.1f" (/ dur 1000.0)) "s "
               (name status)
               (when usage
                 (str " tokens=" (:input-tokens usage)
                      "/" (:output-tokens usage)))))
    (when truncated?
      (log! (str "  Warning: truncated " (:file/path file-map))))
    (-> acc
        (update status (fnil inc 0))
        (update :elapsed-ms (fnil + 0) dur)
        (update :total-usage llm/sum-usage (or usage llm/zero-usage)))))

(def ^:private avg-input-tokens-per-file 1250)
(def ^:private avg-output-tokens-per-file 217)
(def ^:private avg-ms-per-file 18000)

(defn- format-tokens
  "Format token count with k/M suffix for readability."
  [n]
  (cond
    (< n 1000)    (str n)
    (< n 1000000) (format "~%dk" (Math/round (/ n 1000.0)))
    :else         (format "~%.1fM" (/ n 1e6))))

(defn- format-cost [usd]
  (format "$%.2f" usd))

(defn- estimate-banner
  "Print pre-run estimate of tokens, cost, and time."
  [total model-id]
  (let [est-in   (* total avg-input-tokens-per-file)
        est-out  (* total avg-output-tokens-per-file)
        est-cost (llm/estimate-cost model-id est-in est-out)
        est-time (format-eta (* total avg-ms-per-file))
        cost-str (when (pos? est-cost) (str " (" (format-cost est-cost) " USD)"))]
    (log! (str "  Estimated: " (format-tokens est-in) " input / "
               (format-tokens est-out) " output tokens"
               cost-str ", " est-time))))

(defn analyze-repo!
  "Analyze all source files in repo-path that need analysis.
   `invoke-llm` is a function (prompt -> {:text string :usage map}) for testability.
   `opts` may include :model-id for cost estimation.
   Returns summary map with :total-usage."
  ([conn repo-path invoke-llm] (analyze-repo! conn repo-path invoke-llm {}))
  ([conn repo-path invoke-llm {:keys [model-id]}]
   (let [db            (d/db conn)
         files         (files-needing-analysis db)
         total         (count files)
         prompt-map    (load-prompt-template)
         analysis-opts {:prompt-template (:template prompt-map)
                        :prompt-hash-val (prompt-hash (:template prompt-map))
                        :invoke-llm invoke-llm}
         start-ms      (System/currentTimeMillis)]
     (if (zero? total)
       (do (log! "All files already analyzed, nothing to do.")
           {:files-analyzed 0 :files-skipped 0
            :files-parse-errored 0 :files-errored 0
            :elapsed-ms 0 :total-usage llm/zero-usage})
       (do
         (log! (str "Analyzing " total " files..."))
         (estimate-banner total model-id)
         (let [results (reduce (partial analyze-one-file! conn repo-path analysis-opts total)
                               {:ok 0 :parse-error 0 :error 0 :elapsed-ms 0 :total-usage llm/zero-usage}
                               (map-indexed vector files))
               elapsed (- (System/currentTimeMillis) start-ms)
               tu      (:total-usage results)
               cost    (:cost-usd tu 0.0)]
           (log! (str "Done. " (:ok results 0) " analyzed"
                      (when (pos? (:parse-error results 0))
                        (str ", " (:parse-error results 0) " parse errors (retryable)"))
                      (when (pos? (:error results 0))
                        (str ", " (:error results 0) " errors"))
                      ". tokens=" (:input-tokens tu) "/" (:output-tokens tu)
                      (when (pos? cost) (str " cost=" (format-cost cost)))
                      " (" (format-eta elapsed) ")"))
           {:files-analyzed      (:ok results 0)
            :files-skipped       0
            :files-parse-errored (:parse-error results 0)
            :files-errored       (:error results 0)
            :elapsed-ms          elapsed
            :total-usage         tu}))))))
