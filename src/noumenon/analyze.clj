(ns noumenon.analyze
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.files :as files]
            [noumenon.llm :as llm]
            [noumenon.pipeline :as pipeline]
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

(defn- format-import-context
  "Format resolved imports and reverse imports as context strings.
   Returns a map with :imports and :imported-by strings, or empty strings if none."
  [db file-path]
  (let [forward (->> (d/pull db [{:file/imports [:file/path]}] [:file/path file-path])
                     :file/imports
                     (mapv :file/path)
                     sort)
        reverse (->> (d/q '[:find ?p
                            :in $ ?target
                            :where
                            [?other :file/imports ?target]
                            [?other :file/path ?p]]
                          db [:file/path file-path])
                     (mapv first)
                     sort)]
    {:imports     (if (seq forward) (str/join "\n" forward) "")
     :imported-by (if (seq reverse) (str/join "\n" reverse) "")}))

(defn render-prompt
  "Substitute template variables into the prompt template string.
   Content is escaped and wrapped in delimiters to prevent prompt injection."
  [template {:keys [file-path lang line-count content repo-name imports imported-by]}]
  (let [safe-content (str "<file-content>\n"
                          (escape-template-vars content)
                          "\n</file-content>")
        imports-section (if (str/blank? imports)
                          ""
                          (str "\nKnown imports (resolved file paths):\n" imports "\n"))
        imported-by-section (if (str/blank? imported-by)
                              ""
                              (str "\nFiles that depend on this file:\n" imported-by "\n"))]
    (-> template
        (str/replace "{{file-path}}" (escape-template-vars (or file-path "")))
        (str/replace "{{lang}}" (name (or lang :unknown)))
        (str/replace "{{lang-name}}" (name (or lang :unknown)))
        (str/replace "{{line-count}}" (str (or line-count 0)))
        (str/replace "{{content}}" safe-content)
        (str/replace "{{repo-name}}" (escape-template-vars (or repo-name "")))
        (str/replace "{{imports}}" imports-section)
        (str/replace "{{imported-by}}" imported-by-section))))

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
  [file-path analysis {:keys [model-version prompt-hash-val analyzer usage]}]
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
          prov-tx  (cond-> {:db/id               "datomic.tx"
                            :tx/op               :analyze
                            :tx/source           :llm
                            :tx/model            (or model-version "unknown")
                            :tx/analyzer         (or analyzer "noumenon.analyze/0.1.0")
                            :prov/model-version  (or model-version "unknown")
                            :prov/prompt-hash    (or prompt-hash-val "")
                            :prov/analyzed-at    (Date.)}
                     (:input-tokens usage)  (assoc :tx/input-tokens (:input-tokens usage))
                     (:output-tokens usage) (assoc :tx/output-tokens (:output-tokens usage))
                     (:cost-usd usage)      (assoc :tx/cost-usd (:cost-usd usage)))]
      (into [file-tx prov-tx] seg-txs))))

;; --- File content ---

(defn- valid-git-path?
  "Return true when file-path is safe to embed in a git refspec."
  [file-path]
  (not (or (str/includes? file-path ":")
           (str/includes? file-path "\0"))))

(defn git-show
  "Read file content from HEAD via `git show HEAD:<path>`."
  [repo-path file-path]
  (when-not (valid-git-path? file-path)
    (throw (ex-info "Invalid file path for git-show"
                    {:path file-path})))
  (let [{:keys [exit out err]} (shell/sh "git" "-C" (str repo-path)
                                         "show" (str "HEAD:" file-path))]
    (when (not= 0 exit)
      (throw (ex-info (str "git show failed: " (str/trim (or err "")))
                      {:exit exit :file-path file-path})))
    out))

;; --- Skip logic ---

(defn files-needing-analysis
  "Return file entities (as maps with :file/path and :file/lang) that have
   a recognized language but no :sem/summary yet.  Excludes sensitive files."
  [db]
  (let [candidates (->> (d/q '[:find ?path ?lang
                               :where
                               [?e :file/path ?path]
                               [?e :file/lang ?lang]
                               (not [?e :sem/summary _])]
                             db)
                        (mapv (fn [[path lang]] {:file/path path :file/lang lang})))
        {sensitive true safe false} (group-by #(files/sensitive-path? (:file/path %))
                                              candidates)]
    (when (seq sensitive)
      (log! (str "Skipping " (count sensitive) " sensitive file(s) from analysis")))
    (sort-by :file/path safe)))

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
   Retries once on parse errors (unparseable LLM response).
   When enriched data (`:file/imports`) is available, includes it as context."
  [conn repo-path file-map {:keys [prompt-template prompt-hash-val invoke-llm]}]
  (let [{:keys [file/path file/lang]} file-map
        repo-name  (repo-name repo-path)
        usage-atom (atom nil)]
    (try
      (let [db                   (d/db conn)
            raw-content          (git-show repo-path path)
            [content truncated?] (truncate-content raw-content)
            {:keys [imports imported-by]} (format-import-context db path)
            prompt   (render-prompt prompt-template
                                    {:file-path    path
                                     :lang         lang
                                     :line-count   (count (str/split-lines content))
                                     :content      content
                                     :repo-name    repo-name
                                     :imports      imports
                                     :imported-by  imported-by})
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
        (reset! usage-atom (:usage result))
        (if-let [analysis (:analysis result)]
          (let [tx-data (analysis->tx-data path analysis
                                           {:model-version   (or (:resolved-model result) "unknown")
                                            :prompt-hash-val prompt-hash-val
                                            :analyzer        "noumenon.analyze/0.1.0"
                                            :usage           (:usage result)})]
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
  "Analyze one file with progress logging. Updates stats-atom in place."
  [conn repo-path analysis-opts total stats-atom [_idx file-map]]
  (let [start  (System/currentTimeMillis)
        result (analyze-file! conn repo-path file-map analysis-opts)
        {:keys [status usage truncated?]} result
        dur    (- (System/currentTimeMillis) start)
        path   (:file/path file-map)
        n      (swap! stats-atom
                      (fn [s]
                        (cond-> (-> s
                                    (update :started (fnil inc 0))
                                    (update status (fnil inc 0))
                                    (update :elapsed-ms (fnil + 0) dur)
                                    (update :total-usage llm/sum-usage (or usage llm/zero-usage)))
                          (= :parse-error status)
                          (update :parse-error-paths (fnil conj []) path))))]
    (log! (str "  [" (:started n) "/" total "] "
               path " "
               (format "%.1f" (/ dur 1000.0)) "s "
               (name status)
               (when usage
                 (str " tokens=" (:input-tokens usage)
                      "/" (:output-tokens usage)))))
    (when truncated?
      (log! (str "  Warning: truncated " path)))))

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
   `opts` may include :model-id, :concurrency, :min-delay-ms.
   Returns summary map with :total-usage."
  ([conn repo-path invoke-llm] (analyze-repo! conn repo-path invoke-llm {}))
  ([conn repo-path invoke-llm {:keys [model-id concurrency min-delay-ms max-files]
                               :or   {concurrency 3 min-delay-ms 0}}]
   (let [db            (d/db conn)
         all-files     (files-needing-analysis db)
         files         (if max-files (vec (take max-files all-files)) all-files)
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
         (log! (str "Analyzing " total " files"
                    (when (> concurrency 1) (str " (concurrency=" concurrency ")"))
                    "..."))
         (estimate-banner total model-id)
         (let [stats-atom  (atom {:ok 0 :parse-error 0 :error 0 :started 0
                                  :elapsed-ms 0 :total-usage llm/zero-usage})
               stop-flag   (atom nil)
               error-atom  (atom nil)
               items       (vec (map-indexed vector files))
               before-fn   (when (pos? min-delay-ms)
                             (let [next-allowed (atom 0)]
                               (fn [_]
                                 (let [now  (System/currentTimeMillis)
                                       slot (swap! next-allowed
                                                   #(max (+ % min-delay-ms) now))
                                       wait (- slot now)]
                                   (when (pos? wait) (Thread/sleep wait))))))]
           (pipeline/run-concurrent!
            items
            {:concurrency    concurrency
             :stop-flag      stop-flag
             :error-atom     error-atom
             :before-item!   before-fn
             :process-item!  (fn [item]
                               (analyze-one-file! conn repo-path analysis-opts
                                                  total stats-atom item))})
           (let [results    @stats-atom
                 elapsed    (- (System/currentTimeMillis) start-ms)
                 tu         (:total-usage results)
                 cost       (:cost-usd tu 0.0)
                 pe-paths   (:parse-error-paths results [])]
             (log! (str "Done. " (:ok results 0) " analyzed"
                        (when (pos? (:parse-error results 0))
                          (str ", " (:parse-error results 0) " parse errors (re-run analyze to retry)"))
                        (when (pos? (:error results 0))
                          (str ", " (:error results 0) " errors"))
                        ". tokens=" (:input-tokens tu) "/" (:output-tokens tu)
                        (when (pos? cost) (str " cost=" (format-cost cost)))
                        " (" (format-eta elapsed) ")"))
             (when (seq pe-paths)
               (log! (str "  Parse-errored files: " (str/join ", " pe-paths))))
             {:files-analyzed      (:ok results 0)
              :files-skipped       0
              :files-parse-errored (:parse-error results 0)
              :parse-error-paths   pe-paths
              :files-errored       (:error results 0)
              :elapsed-ms          elapsed
              :total-usage         tu})))))))
