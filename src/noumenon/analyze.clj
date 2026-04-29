(ns noumenon.analyze
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.llm :as llm]
            [noumenon.pipeline :as pipeline]
            [noumenon.selector :as selector]
            [noumenon.util :as util :refer [escape-double-mustache log! sha256-hex]])
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
  #{:trivial :simple :moderate :complex :very-complex})

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

(def ^:private valid-tags
  #{:io :parsing :concurrency :configuration :testing :networking
    :validation :logging :security :routing :middleware :state-management})

(def ^:private valid-patterns
  #{:state-machine :producer-consumer :builder :factory :decorator
    :adapter :pipeline :ring-handler :middleware-chain})

;; --- Prompt ---

(defn load-prompt-template
  "Load the analyze-file prompt template from Datomic. Returns {:template str :version str}."
  [meta-db]
  {:template (artifacts/load-prompt meta-db "analyze-file")
   :version  (artifacts/load-prompt-version meta-db "analyze-file")})

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
                          (-> content
                              escape-double-mustache
                              (str/replace "</file-content>" "&lt;/file-content&gt;"))
                          "\n</file-content>")
        imports-section (if (str/blank? imports)
                          ""
                          (str "\nKnown imports (resolved file paths):\n" imports "\n"))
        imported-by-section (if (str/blank? imported-by)
                              ""
                              (str "\nFiles that depend on this file:\n" imported-by "\n"))
        bindings {"file-path"    (or file-path "")
                  "lang"         (name (or lang :unknown))
                  "lang-name"    (name (or lang :unknown))
                  "line-count"   (str (or line-count 0))
                  "content"      safe-content
                  "repo-name"    (or repo-name "")
                  "imports"      imports-section
                  "imported-by"  imported-by-section}]
    (str/replace template #"\{\{([^}]+)\}\}"
                 (fn [[match key]] (get bindings key match)))))

;; --- Defensive EDN parsing ---

(defn- clamp
  "Truncate string to max-string-length if needed."
  [s]
  (cond-> s (string? s) (util/truncate max-string-length)))

(defn strip-markdown-fences
  "Remove markdown code fences from LLM output. Returns empty string for nil."
  [s]
  (if-not s
    ""
    (-> s
        str/trim
        (str/replace #"^```\w*\n?" "")
        (str/replace #"\n?```$" "")
        str/trim)))

(defn- non-blank? [s] (and (string? s) (not (str/blank? s))))

(defn- validate-segment
  "Validate a segment map. Returns cleaned segment or nil."
  [{:keys [name kind line-start line-end
           args returns visibility docstring deprecated
           complexity smells purpose safety-concerns error-handling
           call-names pure ai-likelihood]}]
  (when (non-blank? name)
    (let [valid-smells'   (seq (filter valid-smells smells))
          valid-safety    (seq (filter valid-safety-concerns safety-concerns))
          valid-calls     (seq (filter non-blank? call-names))]
      (cond-> {:name (clamp name)}
        (valid-code-kind kind)              (assoc :kind kind)
        (integer? line-start)               (assoc :line-start line-start)
        (integer? line-end)                 (assoc :line-end line-end)
        (non-blank? args)                   (assoc :args (clamp args))
        (non-blank? returns)                (assoc :returns (clamp returns))
        (valid-visibility visibility)       (assoc :visibility visibility)
        (non-blank? docstring)              (assoc :docstring (clamp docstring))
        (true? deprecated)                  (assoc :deprecated true)
        (valid-segment-complexity complexity) (assoc :complexity complexity)
        valid-smells'                       (assoc :smells (vec valid-smells'))
        (non-blank? purpose)                (assoc :purpose (clamp purpose))
        valid-safety                        (assoc :safety-concerns (vec valid-safety))
        (valid-error-handling error-handling) (assoc :error-handling error-handling)
        valid-calls                         (assoc :call-names (vec valid-calls))
        (true? pure)                        (assoc :pure true)
        (valid-ai-likelihood ai-likelihood) (assoc :ai-likelihood ai-likelihood)))))

(def ^:private analysis-sanitizers
  {:summary      #(when (string? %) (clamp %))
   :purpose      #(when (string? %) (clamp %))
   :tags         #(when (and (coll? %) (seq %)) (vec (filter valid-tags %)))
   :complexity   #(when (valid-complexity %) %)
   :patterns     #(when (and (coll? %) (seq %)) (vec (filter valid-patterns %)))
   :layer        #(when (valid-layer %) %)
   :confidence   #(when (and (number? %) (<= 0.0 % 1.0)) (double %))
   :category     #(when (valid-category %) %)
   :dependencies (fn [v] (when (and (coll? v) (seq v))
                           (vec (filter non-blank? v))))
   :architectural-notes #(when (string? %) (subs % 0 (min 2000 (count %))))
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
                        (let [result (edn/read-string {:readers {}} s)]
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

(defn- build-synthesis-hints
  "Bundle file-level architectural guesses into an EDN string for the synthesize step."
  [{:keys [layer category patterns purpose architectural-notes]}]
  (let [hints (cond-> {}
                layer               (assoc :layer-guess layer)
                category            (assoc :category-guess category)
                (seq patterns)      (assoc :patterns-observed patterns)
                purpose             (assoc :purpose-guess purpose)
                architectural-notes (assoc :notes architectural-notes))]
    (when (seq hints) (pr-str hints))))

(defn- deduplicate-segments
  "Deduplicate segments by name, appending :L<line> for collisions. Pure."
  [segments]
  (->> segments
       (reduce (fn [acc {:keys [name line-start] :as seg}]
                 (let [unique-name (if (contains? (:seen acc) name)
                                     (str name ":L" (or line-start (count (:seen acc))))
                                     name)]
                   (if (contains? (:seen acc) unique-name)
                     acc
                     (-> acc
                         (update :seen conj name unique-name)
                         (update :txs conj (assoc seg :name unique-name))))))
               {:seen #{} :txs []})
       :txs))

(defn- build-file-tx
  "Build the file entity transaction map from analysis results. Pure."
  [file-path analysis]
  (let [{:keys [summary purpose tags complexity patterns layer
                category confidence]} analysis
        hints (build-synthesis-hints analysis)]
    (cond-> {:file/path file-path}
      summary            (assoc :sem/summary summary)
      purpose            (assoc :sem/purpose purpose)
      (seq tags)         (assoc :sem/tags tags)
      complexity         (assoc :sem/complexity complexity)
      (seq patterns)     (assoc :sem/patterns patterns)
      layer              (assoc :arch/layer layer)
      category           (assoc :sem/category category)
      confidence         (assoc :prov/confidence confidence)
      hints              (assoc :sem/synthesis-hints hints))))

(defn- build-analyze-prov-tx
  "Build the provenance transaction entity for an analysis run. Pure."
  [{:keys [model-version prompt-hash-val analyzer usage]}]
  (let [cost (let [raw (:cost-usd usage 0.0)]
               (if (pos? raw)
                 raw
                 (llm/estimate-cost (or model-version "")
                                    (:input-tokens usage 0)
                                    (:output-tokens usage 0))))]
    (cond-> {:db/id              "datomic.tx"
             :tx/op              :analyze
             :tx/source          :llm
             :tx/model           (or model-version "unknown")
             :tx/analyzer        (or analyzer "noumenon.analyze/0.1.0")
             :prov/model-version (or model-version "unknown")
             :prov/prompt-hash   (or prompt-hash-val "")
             :prov/analyzed-at   (Date.)}
      (:input-tokens usage)  (assoc :tx/input-tokens (:input-tokens usage))
      (:output-tokens usage) (assoc :tx/output-tokens (:output-tokens usage))
      (pos? cost)            (assoc :tx/cost-usd cost))))

(defn analysis->tx-data
  "Convert a parsed analysis map into Datomic tx-data for a file.
   `file-path` is the repo-relative path. Returns tx-data vector (may be empty)."
  [file-path analysis prov-opts]
  (when (and analysis (seq analysis))
    (let [file-ref [:file/path file-path]
          seg-txs  (when-let [segs (seq (:segments analysis))]
                     (mapv (partial segment->tx-data file-ref)
                           (deduplicate-segments segs)))]
      (into [(build-file-tx file-path analysis)
             (build-analyze-prov-tx prov-opts)]
            seg-txs))))

;; --- File content ---

(defn- valid-git-path?
  "Return true when file-path is safe to embed in a git refspec."
  [file-path]
  (not (or (str/starts-with? file-path "-")
           (str/includes? file-path ":")
           (str/includes? file-path "\0"))))

(defn git-show
  "Read file content from HEAD via `git show HEAD:<path>`."
  [repo-path file-path]
  (when-not (valid-git-path? file-path)
    (throw (ex-info "Invalid file path for git-show"
                    {:path file-path})))
  (let [args (into ["git"] (concat (git/git-dir-args repo-path)
                                   ["show" (str "HEAD:" file-path)]))
        {:keys [exit out err]} (apply shell/sh args)]
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

(def ^:private valid-reanalyze-scopes
  #{:all :prompt-changed :model-changed :stale})

(defn files-for-reanalysis
  "Return analyzed files matching `scope` for re-analysis.
   `opts` may include :prompt-hash (for :prompt-changed) and :model-id (for :model-changed).
   Returns [{:file/path ... :file/lang ...}], same shape as `files-needing-analysis`."
  [db scope opts]
  {:pre [(valid-reanalyze-scopes scope)]}
  (let [raw (case scope
              :all
              (d/q '[:find ?path ?lang
                     :where
                     [?e :file/path ?path]
                     [?e :file/lang ?lang]
                     [?e :sem/summary _]]
                   db)

              :prompt-changed
              (d/q '[:find ?path ?lang
                     :in $ ?current-hash
                     :where
                     [?e :file/path ?path]
                     [?e :file/lang ?lang]
                     [?e :sem/summary _ ?tx]
                     [?tx :prov/prompt-hash ?h]
                     [(not= ?h ?current-hash)]]
                   db (:prompt-hash opts))

              :model-changed
              (d/q '[:find ?path ?lang
                     :in $ ?current-model
                     :where
                     [?e :file/path ?path]
                     [?e :file/lang ?lang]
                     [?e :sem/summary _ ?tx]
                     [?tx :prov/model-version ?m]
                     [(not= ?m ?current-model)]]
                   db (:model-id opts))

              :stale
              (d/q '[:find ?path ?lang
                     :where
                     [?e :file/path ?path]
                     [?e :file/lang ?lang]
                     [?e :sem/summary _ ?tx]
                     [?tx :prov/analyzed-at ?at]
                     [?c :commit/changed-files ?e]
                     [?c :commit/committed-at ?ct]
                     [(> ?ct ?at)]]
                   db))
        candidates (mapv (fn [[path lang]] {:file/path path :file/lang lang}) raw)
        {sensitive true safe false} (group-by #(files/sensitive-path? (:file/path %))
                                              candidates)]
    (when (seq sensitive)
      (log! (str "Skipping " (count sensitive) " sensitive file(s) from re-analysis")))
    (sort-by :file/path safe)))

(defn- log-drift-recommendations!
  "Log optional re-analysis recommendations when prompt/model drift is detected."
  [db prompt-hash-val model-id]
  (let [prompt-n (count (files-for-reanalysis db :prompt-changed {:prompt-hash prompt-hash-val}))
        model-n  (if model-id
                   (count (files-for-reanalysis db :model-changed {:model-id model-id}))
                   0)]
    (when (pos? prompt-n)
      (log! (str "Recommendation: " prompt-n
                 " analyzed file(s) used an older prompt. "
                 "Run analyze with --reanalyze prompt-changed to refresh.")))
    (when (pos? model-n)
      (log! (str "Recommendation: " model-n
                 " analyzed file(s) used a different model. "
                 "Run analyze with --reanalyze model-changed to refresh.")))))

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

(defn- build-file-prompt
  "Build the analysis prompt for a file, including import context."
  [db repo-path file-path lang prompt-template]
  (let [raw-content          (git-show repo-path file-path)
        [content truncated?] (truncate-content raw-content)
        {:keys [imports imported-by]} (format-import-context db file-path)]
    {:prompt     (render-prompt prompt-template
                                {:file-path    file-path
                                 :lang         lang
                                 :line-count   (count (str/split-lines content))
                                 :content      content
                                 :repo-name    (repo-name repo-path)
                                 :imports      imports
                                 :imported-by  imported-by})
     :truncated? truncated?}))

(defn- invoke-with-retry
  "Invoke LLM and retry once on parse failure. Returns result map with merged usage."
  [invoke-llm prompt truncated? path]
  (let [invoke-once (fn []
                      (let [{:keys [text usage resolved-model]} (invoke-llm prompt)]
                        {:text text :usage usage :resolved-model resolved-model
                         :analysis (parse-llm-response text) :truncated? truncated?}))
        r1 (invoke-once)]
    (if (:analysis r1)
      r1
      (do (log! (str "  Retrying " path " (unparseable response)..."))
          (let [r2 (invoke-once)]
            ;; Preserve first call's resolved-model for provenance accuracy
            (-> r2
                (assoc :resolved-model (:resolved-model r1))
                (update :usage #(llm/sum-usage (:usage r1) %))))))))

(defn analyze-file!
  "Analyze a single file. Returns {:status :ok/:parse-error/:error, :usage map-or-nil}.
   Retries once on parse errors (unparseable LLM response)."
  [conn repo-path file-map {:keys [prompt-template prompt-hash-val invoke-llm]}]
  (let [{:keys [file/path file/lang]} file-map]
    (try
      (let [{:keys [prompt truncated?]} (build-file-prompt (d/db conn) repo-path
                                                           path lang prompt-template)
            result (invoke-with-retry invoke-llm prompt truncated? path)]
        (if-let [analysis (:analysis result)]
          (let [tx-data (analysis->tx-data path analysis
                                           {:model-version   (or (:resolved-model result) "unknown")
                                            :prompt-hash-val prompt-hash-val
                                            :analyzer        "noumenon.analyze/0.1.0"
                                            :usage           (:usage result)})]
            (d/transact conn {:tx-data tx-data})
            {:status :ok :usage (:usage result) :truncated? truncated?})
          (do (log! (str "WARNING: unparseable response for " path ", skipping"))
              {:status :parse-error :usage (:usage result) :truncated? truncated?})))
      (catch Exception e
        (log! (str "  Error analyzing " path ": " (.getMessage e)))
        {:status :error}))))

(defn- format-eta
  "Format estimated time remaining as a human-readable string."
  [eta-ms]
  (let [secs (/ eta-ms 1000.0)]
    (cond
      (< secs 60)   (str (Math/round secs) "s")
      (< secs 3600) (str (Math/round (/ secs 60.0)) "m")
      :else         (format "%.1fh" (/ secs 3600.0)))))

(defn- update-stats
  "Update stats atom with result of one file analysis. Returns new stats."
  [stats-atom {:keys [status usage truncated? path dur]}]
  (swap! stats-atom
         (fn [s]
           (cond-> (-> s
                       (update :started (fnil inc 0))
                       (update status (fnil inc 0))
                       (update :elapsed-ms (fnil + 0) dur)
                       (update :total-usage llm/sum-usage (or usage llm/zero-usage)))
             (= :parse-error status) (update :parse-error-paths (fnil conj []) path)
             truncated?              (update :truncated (fnil inc 0))))))

(defn- analyze-one-file!
  "Analyze one file with progress logging. Updates stats-atom in place."
  [{:keys [conn repo-path analysis-opts total stats-atom progress-fn]} [_idx file-map]]
  (let [start  (System/currentTimeMillis)
        result (analyze-file! conn repo-path file-map analysis-opts)
        dur    (- (System/currentTimeMillis) start)
        path   (:file/path file-map)
        n      (update-stats stats-atom (assoc result :path path :dur dur))]
    (log! (str "  [" (:started n) "/" total "] "
               path " "
               (format "%.1f" (/ dur 1000.0)) "s "
               (name (:status result))
               (when-let [u (:usage result)]
                 (str " tokens=" (:input-tokens u) "/" (:output-tokens u)))))
    (when progress-fn
      (progress-fn {:current (:started n) :total total :message path}))
    (when (:truncated? result)
      (log! (str "WARNING: truncated " path)))))

;; Per-language averages from 2,396 files across 9 repos (2026-03-27).
;; Fallback used for languages not yet profiled.
(def ^:private lang-token-profile
  {:clojure    {:input 3980 :output 778 :ms 2438}
   :python     {:input 3857 :output 718 :ms 2258}
   :javascript {:input 3572 :output 648 :ms 2047}
   :typescript {:input 3441 :output 476 :ms 1724}
   :c          {:input 4927 :output 801 :ms 3297}
   :cpp        {:input 4927 :output 801 :ms 3297}
   :go         {:input 5469 :output 840 :ms 3535}
   :rust       {:input 6038 :output 932 :ms 5193}
   :java       {:input 4567 :output 743 :ms 2966}})

(def ^:private default-token-profile
  {:input 4567 :output 743 :ms 2966})

(defn- estimate-for-files
  "Compute per-file estimates using language-specific profiles."
  [files]
  (reduce (fn [acc {:file/keys [lang]}]
            (let [profile (get lang-token-profile lang default-token-profile)]
              (-> acc
                  (update :input + (:input profile))
                  (update :output + (:output profile))
                  (update :ms + (:ms profile)))))
          {:input 0 :output 0 :ms 0}
          files))

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
  [files model-id]
  (let [{:keys [input output ms]} (estimate-for-files files)
        est-cost (llm/estimate-cost model-id input output)
        est-time (format-eta ms)
        cost-str (when (pos? est-cost) (str " (" (format-cost est-cost) " USD)"))]
    (log! (str "  Estimated: " (format-tokens input) " input / "
               (format-tokens output) " output tokens"
               cost-str ", " est-time))))

(def ^:private empty-analysis-result
  {:files-analyzed 0 :files-skipped 0
   :files-parse-errored 0 :files-errored 0
   :elapsed-ms 0 :total-usage llm/zero-usage})

(defn- build-rate-limiter
  "Build a before-item fn that enforces min-delay-ms between starts."
  [min-delay-ms]
  (when (pos? min-delay-ms)
    (let [next-allowed (atom 0)]
      (fn [_]
        (let [now  (System/currentTimeMillis)
              slot (swap! next-allowed #(max (+ % min-delay-ms) now))
              wait (- slot now)]
          (when (pos? wait) (Thread/sleep wait)))))))

(defn- format-analysis-summary
  "Log and return the final analysis summary from stats."
  [results elapsed]
  (let [tu       (:total-usage results)
        cost     (:cost-usd tu 0.0)
        pe-paths (:parse-error-paths results [])]
    (log! (str "Done. " (:ok results 0) " analyzed"
               (when (pos? (:truncated results 0))
                 (str ", " (:truncated results 0) " truncated"))
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
     :total-usage         tu}))

(defn analyze-repo!
  "Analyze all source files in repo-path that need analysis.
   `invoke-llm` is a function (prompt -> {:text string :usage map}) for testability.
   `opts` may include :model-id, :concurrency, :min-delay-ms.
   Returns summary map with :total-usage."
  ([conn repo-path invoke-llm] (analyze-repo! conn repo-path invoke-llm {}))
  ([conn repo-path invoke-llm {:keys [meta-db model-id concurrency min-delay-ms max-files progress-fn
                                      path include exclude lang]
                                :or   {concurrency 3 min-delay-ms 0}}]
   (let [head-paths    (into #{} (map :path) (files/parse-ls-tree (files/git-ls-tree repo-path)))
         prompt-map    (load-prompt-template meta-db)
         prompt-h      (prompt-hash (:template prompt-map))
         dbv           (d/db conn)
         all-files     (->> (files-needing-analysis dbv)
                             (filterv (comp head-paths :file/path)))
         filters       (selector/normalize repo-path {:path path :include include
                                                      :exclude exclude :lang lang})
         {:keys [files summary]} (selector/apply-filters all-files filters)
         files         (if max-files (vec (take max-files files)) files)
         total         (count files)
         analysis-opts {:prompt-template (:template prompt-map)
                        :prompt-hash-val prompt-h
                        :invoke-llm invoke-llm}]
     (log-drift-recommendations! dbv prompt-h model-id)
     (when (pos? (:excluded summary 0))
       (log! (str "Selection filters excluded " (:excluded summary) " file(s).")))
     (if (zero? total)
       (do (log! "All files already analyzed, nothing to do.")
           empty-analysis-result)
       (do
         (log! (str "Analyzing " total " files"
                    (when (> concurrency 1) (str " (concurrency=" concurrency ")"))
                    "..."))
         (estimate-banner files model-id)
         (let [stats-atom (atom {:ok 0 :parse-error 0 :error 0 :started 0
                                 :elapsed-ms 0 :total-usage llm/zero-usage})
               ctx        {:conn conn :repo-path repo-path
                           :analysis-opts analysis-opts
                           :total total :stats-atom stats-atom
                           :progress-fn progress-fn}
               start-ms   (System/currentTimeMillis)]
           (pipeline/run-concurrent!
            (vec (map-indexed vector files))
            {:concurrency   concurrency
             :stop-flag     (atom nil)
             :error-atom    (atom nil)
             :before-item!  (build-rate-limiter min-delay-ms)
             :process-item! (partial analyze-one-file! ctx)})
           (format-analysis-summary @stats-atom
                                    (- (System/currentTimeMillis) start-ms))))))))
