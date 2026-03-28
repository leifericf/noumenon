(ns noumenon.mcp
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.db :as db]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.benchmark :as bench]
            [noumenon.imports :as imports]
            [noumenon.introspect :as introspect]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]])
  (:import [java.io BufferedReader PrintWriter]))

;; --- Helpers ---

(defn- validate-repo-path!
  "Validate that repo-path exists, is a directory, and contains .git.
   Throws ex-info with a generic message (details logged to stderr only)."
  [repo-path]
  (when-let [reason (util/validate-repo-path repo-path)]
    (log! "validate-repo-path" reason repo-path)
    (throw (ex-info "Invalid or inaccessible repository path"
                    {:repo-path    repo-path
                     :user-message "Invalid or inaccessible repository path."}))))

;; --- Connection cache ---

(defonce ^:private connections (atom {}))

;; --- Async introspect sessions ---

(defonce ^:private introspect-sessions (atom {}))
;; {run-id {:status :running/:completed/:stopped/:error
;;          :future <future> :result <map> :stop-flag <atom<bool>>}}

(defn- get-or-create-conn
  "Get or create a connection, keyed on [db-dir db-name].
   Uses locking to avoid retrying side-effecting db/connect-and-ensure-schema."
  [db-dir db-name]
  (let [cache-key [db-dir db-name]]
    (or (get @connections cache-key)
        (locking connections
          (or (get @connections cache-key)
              (let [conn (db/connect-and-ensure-schema db-dir db-name)]
                (swap! connections assoc cache-key conn)
                conn))))))

;; --- JSON-RPC plumbing ---

(defn- format-response [id result]
  (json/write-str {:jsonrpc "2.0" :id id :result result}))

(defn- format-error [id code message]
  (json/write-str {:jsonrpc "2.0" :id id
                   :error {:code code :message message}}))

(defn- tool-result [text]
  {:content [{:type "text" :text text}]})

(defn- tool-error [text]
  {:content [{:type "text" :text text}] :isError true})

;; --- Input validation ---

(def ^:private max-repo-path-len 4096)
(def ^:private max-question-len 8000)
(def ^:private max-model-len 256)
(def ^:private max-provider-len 64)
(def ^:private max-layers-len 64)
(def ^:private allowed-layers #{:raw :import :enrich :full})

(defn- validate-string-length!
  "Throw ex-info if s exceeds max-len characters."
  [field-name s max-len]
  (when (> (count s) max-len)
    (throw (ex-info (str field-name " exceeds maximum length of " max-len " characters")
                    {:field field-name :length (count s) :max max-len}))))

(defn- validate-llm-inputs!
  "Validate model and provider string lengths when present."
  [args]
  (when-let [m (args "model")] (validate-string-length! "model" m max-model-len))
  (when-let [p (args "provider")] (validate-string-length! "provider" p max-provider-len)))

(defn- validate-layers
  "Parse and validate a comma-separated layers string. Returns keyword vector or nil."
  [layers-str]
  (when layers-str
    (validate-string-length! "layers" layers-str max-layers-len)
    (let [kws (mapv keyword (str/split layers-str #","))]
      (when-let [bad (seq (remove allowed-layers kws))]
        (throw (ex-info (str "Unknown layers: " (pr-str bad)
                             ". Valid: raw, import, enrich, full")
                        {:user-message (str "Unknown layers: " (pr-str bad)
                                            ". Valid: raw, import, enrich, full")})))
      kws)))

;; --- Tool definitions ---

(def ^:private repo-path-prop
  {"repo_path" {:type "string" :description "Absolute path to git repository"}})

;; db-dir-prop removed: db-dir is server-level config only

(def ^:private tools
  [{:name "noumenon_import"
    :description "Import a git repository's commit history and file structure into the knowledge graph. Safe to call on already-imported repositories — only processes new commits and files."
    :inputSchema {:type "object"
                  :properties repo-path-prop
                  :required ["repo_path"]}}
   {:name "noumenon_status"
    :description "Get entity counts (commits, files, directories) and the HEAD SHA of the last imported commit. Compare with `git rev-parse HEAD` to check if the knowledge graph is up to date."
    :inputSchema {:type "object"
                  :properties repo-path-prop
                  :required ["repo_path"]}}
   {:name "noumenon_query"
    :description "Run a named Datalog query against the knowledge graph. Some queries require params — use noumenon_list_queries to see which."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"query_name" {:type "string"
                                                    :description "Named query — call noumenon_list_queries first to see available names"}
                                      "params" {:type "object"
                                                :description "Optional parameters for parameterized queries (string keys and values)"
                                                :additionalProperties {:type "string"}}
                                      "limit" {:type "integer"
                                               :description "Maximum number of result rows to return (default 500, max 10000)"}})
                  :required ["query_name" "repo_path"]}}
   {:name "noumenon_list_queries"
    :description "List available named Datalog queries"
    :inputSchema {:type "object" :properties {}}}
   {:name "noumenon_get_schema"
    :description "Get the database schema showing all attributes and their types. Requires a repo to have been imported first."
    :inputSchema {:type "object"
                  :properties repo-path-prop
                  :required ["repo_path"]}}
   {:name "noumenon_update"
    :description "Update the knowledge graph with the latest git state. Runs import + enrich for changed files. Fast and cheap (no LLM calls by default). Pass analyze=true to also re-analyze changed files with LLM. Works as a first-time setup too — if no database exists, runs the full pipeline."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"analyze" {:type "boolean"
                                                 :description "Also run LLM analysis on changed files (default: false)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_ask"
    :description "Ask a natural-language question about a repository. Uses an AI agent to run iterative Datalog queries against the knowledge graph. Requires prior import — if the repository has not been imported yet, call noumenon_update first. Uses LLM API calls. For structured queries, prefer noumenon_query."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"question" {:type "string" :description "Question to ask about the repository"}
                                      "provider" {:type "string" :description "LLM provider: glm, claude-api, or claude-cli (aliases: claude = claude-cli)"}
                                      "model" {:type "string" :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "max_iterations" {:type "integer" :description "Max query iterations (default: 10, max: 50)"}
                                      "continue_from" {:type "string" :description "Session ID from a budget-exhausted run — resumes the agent from where it left off"}})
                  :required ["question" "repo_path"]}}
   {:name "noumenon_analyze"
    :description "Run LLM analysis on repository files to enrich the knowledge graph with semantic metadata. By default only analyzes files not yet analyzed. Pass reanalyze to re-analyze files: all, prompt-changed, model-changed, or stale. Requires a prior import."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string"
                                                  :description "LLM provider: glm, claude-api, or claude-cli (aliases: claude = claude-cli)"}
                                      "model" {:type "string"
                                               :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "concurrency" {:type "integer"
                                                     :description "Number of concurrent LLM calls (default: 3, max: 20)"}
                                      "max_files" {:type "integer"
                                                   :description "Stop after analyzing N files (useful for sampling)"}
                                      "reanalyze" {:type "string"
                                                   :description "Re-analyze scope: all, prompt-changed, model-changed, stale (default: only unanalyzed files)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_enrich"
    :description "Extract cross-file import graph deterministically. No LLM calls — uses language-specific parsers. Requires a prior import."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"concurrency" {:type "integer"
                                                     :description "Extraction concurrency (default: 8, max: 20)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_list_databases"
    :description "List all noumenon databases with entity counts, pipeline stages, and cost."
    :inputSchema {:type "object" :properties {}}}
   {:name "noumenon_benchmark_run"
    :description "Run a benchmark comparing LLM answers across knowledge graph layers. WARNING: Expensive — uses many LLM calls. Use max_questions to limit scope."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider: glm, claude-api, or claude-cli"}
                                      "model" {:type "string" :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "max_questions" {:type "integer" :description "Limit to N questions (default: all). Use 2 for a quick canary test."}
                                      "layers" {:type "string" :description "Comma-separated layers: raw,import,enrich,full (default: raw,full)"}
                                      "report" {:type "boolean" :description "Generate Markdown report (default: false)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_benchmark_results"
    :description "Get benchmark results from the database. Returns the latest run by default, or a specific run by ID."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"run_id" {:type "string" :description "Specific run ID (default: latest)"}
                                      "detail" {:type "boolean" :description "Include per-question results (default: false)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_benchmark_compare"
    :description "Compare two benchmark runs, showing score differences per layer."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"run_id_a" {:type "string" :description "First run ID"}
                                      "run_id_b" {:type "string" :description "Second run ID"}})
                  :required ["repo_path" "run_id_a" "run_id_b"]}}
   {:name "noumenon_digest"
    :description "Run the full Noumenon pipeline: import, enrich, analyze (LLM), and benchmark. WARNING: analyze and benchmark steps are expensive (LLM calls). Use skip_analyze and skip_benchmark for a quick structural import. Each step is idempotent."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider"}
                                      "model" {:type "string" :description "Model alias"}
                                      "skip_import" {:type "boolean" :description "Skip the combined import+enrich step (alias for skip_enrich)"}
                                      "skip_enrich" {:type "boolean" :description "Skip the combined import+enrich step (alias for skip_import)"}
                                      "skip_analyze" {:type "boolean" :description "Skip analyze step"}
                                      "skip_benchmark" {:type "boolean" :description "Skip benchmark step"}
                                      "max_questions" {:type "integer" :description "Benchmark: limit to N questions"}
                                      "layers" {:type "string" :description "Benchmark layers: raw,import,enrich,full (default: raw,full)"}
                                      "report" {:type "boolean" :description "Generate Markdown benchmark report"}})
                  :required ["repo_path"]}}
   {:name "noumenon_introspect"
    :description "Run an autonomous self-improvement loop: propose prompt changes, evaluate via benchmark, keep improvements. WARNING: Expensive — runs multiple benchmark evaluations. Use max_iterations to limit scope."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider: glm, claude-api, or claude-cli"}
                                      "model" {:type "string" :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "max_iterations" {:type "integer" :description "Max improvement iterations (default: 10)"}
                                      "max_hours" {:type "number" :description "Stop after N hours of wall-clock time"}
                                      "max_cost" {:type "number" :description "Stop when cost exceeds threshold (dollars)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_introspect_start"
    :description "Start an introspect run asynchronously in the background. Returns a run-id immediately. Use noumenon_introspect_status to check progress and noumenon_introspect_stop to halt."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider"}
                                      "model" {:type "string" :description "Model alias"}
                                      "max_iterations" {:type "integer" :description "Max iterations (default: 10)"}
                                      "max_hours" {:type "number" :description "Stop after N hours"}
                                      "max_cost" {:type "number" :description "Cost threshold"}})
                  :required ["repo_path"]}}
   {:name "noumenon_introspect_status"
    :description "Check the status of a running or completed introspect run."
    :inputSchema {:type "object"
                  :properties {"run_id" {:type "string" :description "Run ID from introspect_start"}}
                  :required ["run_id"]}}
   {:name "noumenon_introspect_stop"
    :description "Stop a running introspect run after the current iteration completes."
    :inputSchema {:type "object"
                  :properties {"run_id" {:type "string" :description "Run ID to stop"}}
                  :required ["run_id"]}}
   {:name "noumenon_introspect_history"
    :description "Query the introspect improvement history from the internal meta database. Available queries: introspect-runs, introspect-improvements, introspect-by-target, introspect-score-trend, introspect-failed-approaches."
    :inputSchema {:type "object"
                  :properties {"query_name" {:type "string"
                                             :description "Named query (use one of the introspect-* queries)"}
                               "limit" {:type "integer"
                                        :description "Maximum result rows (default: 100)"}}
                  :required ["query_name"]}}])

;; --- Tool handlers ---

(defn- with-conn
  "Resolve db-dir and db-name from arguments, get/create connection, call f with conn and db.
   When auto-update is enabled (default), transparently updates stale databases before returning."
  [args defaults f]
  (let [raw-path  (args "repo_path")]
    (validate-string-length! "repo_path" raw-path max-repo-path-len)
    (let [repo-path (.getCanonicalPath (io/file raw-path))]
      (validate-repo-path! repo-path)
      (let [db-dir  (util/resolve-db-dir defaults)
            db-name (util/derive-db-name repo-path)
            conn    (get-or-create-conn db-dir db-name)]
        (when (:auto-update defaults true)
          (let [db (d/db conn)]
            (when (sync/stale? db repo-path)
              (log! "auto-update" "HEAD changed, updating...")
              (sync/update-repo! conn repo-path repo-path {:concurrency 8}))))
        (f {:conn conn :db (d/db conn) :repo-path repo-path :db-name db-name})))))

(defn- format-import-summary [git-r files-r]
  (str "Import complete. "
       (:commits-imported git-r) " commits imported, "
       (:commits-skipped git-r) " skipped. "
       (:files-imported files-r) " files imported, "
       (:files-skipped files-r) " skipped. "
       (:dirs-imported files-r) " directories imported."))

(defn- handle-import [args defaults]
  (with-conn args defaults
    (fn [{:keys [conn repo-path]}]
      (let [git-r   (git/import-commits! conn repo-path repo-path)
            files-r (files/import-files! conn repo-path repo-path)]
        (tool-result (format-import-summary git-r files-r))))))

(defn- handle-status [args defaults]
  (with-conn args defaults
    (fn [{:keys [db]}]
      (let [{:keys [commits files dirs head-sha]} (query/repo-stats db)
            head (if head-sha
                   (str "\nHead: " (subs head-sha 0 (min 7 (count head-sha))))
                   "")]
        (tool-result (str commits " commits, " files " files, " dirs " directories." head))))))

(defn- handle-query [args defaults]
  (validate-string-length! "query_name" (args "query_name") 256)
  (with-conn args defaults
    (fn [{:keys [db]}]
      (let [raw-params (args "params")
            _          (when (> (count raw-params) 20)
                         (throw (ex-info "Too many params"
                                         {:user-message "params: max 20 entries"})))
            _          (doseq [[k v] raw-params]
                         (validate-string-length! (str "params." k) (str v) 1024))
            params     (into {} (map (fn [[k v]] [(keyword k) v])) raw-params)
            result (query/run-named-query db (args "query_name") params)]
        (if (:ok result)
          (let [all-rows   (:ok result)
                total      (count all-rows)
                limit      (min (or (some-> (args "limit") long) 500) 10000)
                rows       (take limit all-rows)
                truncated? (> total limit)
                query-name (args "query_name")
                query-def  (query/load-named-query query-name)
                header     (when-let [cols (:columns query-def)]
                             (str "Columns: " (str/join ", " cols) "\n"))
                summary    (str "Query '" query-name "': " (count rows)
                                (when truncated? (str " of " total))
                                " results\n")]
            (tool-result (str summary header
                              (str/join "\n" (map pr-str rows))
                              (when truncated?
                                (str "\n... truncated (" (- total limit) " more rows). "
                                     "Pass a higher `limit` to see more.")))))
          (tool-error (:error result)))))))

(defn- handle-list-queries [_args _defaults]
  (->> (query/list-query-names)
       (keep (fn [n]
               (when-let [q (query/load-named-query n)]
                 (str n " — " (:description q "no description")
                      (when (seq (:inputs q))
                        (str " [requires params: " (str/join ", " (map name (:inputs q))) "]"))))))
       (str/join "\n")
       tool-result))

(defn- handle-get-schema [args defaults]
  (with-conn args defaults
    (fn [{:keys [db]}]
      (tool-result (query/schema-summary db)))))

(defn- format-update-changes
  "Format update result as a human-readable summary string."
  [result]
  (let [changes (str (when (pos? (:added result 0)) (str " " (:added result) " files added."))
                     (when (pos? (:modified result 0)) (str " " (:modified result) " modified."))
                     (when (pos? (:deleted result 0)) (str " " (:deleted result) " deleted."))
                     (when (pos? (:commits result 0)) (str " " (:commits result) " new commits.")))]
    (if (seq changes)
      (str "Update complete." changes)
      "Already up to date.")))

(defn- handle-update [args defaults]
  (validate-string-length! "repo_path" (args "repo_path") max-repo-path-len)
  (validate-llm-inputs! args)
  (let [raw-path (args "repo_path")
        repo-path (.getCanonicalPath (io/file raw-path))]
    (validate-repo-path! repo-path)
    (let [db-dir   (util/resolve-db-dir defaults)
          db-name  (util/derive-db-name repo-path)
          conn     (get-or-create-conn db-dir db-name)
          analyze? (args "analyze")
          opts     (if analyze?
                     (let [{:keys [prompt-fn model-id]}
                           (llm/wrap-as-prompt-fn-from-opts
                            {:provider (:provider defaults)
                             :model    (:model defaults)})]
                       {:concurrency 8 :analyze? true
                        :model-id model-id :invoke-llm prompt-fn})
                     {:concurrency 8})
          result   (sync/update-repo! conn repo-path repo-path opts)]
      (tool-result (format-update-changes result)))))

(defn- handle-ask [args defaults]
  (validate-string-length! "question" (args "question") max-question-len)
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [db db-name]}]
      (let [{:keys [invoke-fn]}
            (llm/make-messages-fn-from-opts {:provider    (or (args "provider") (:provider defaults))
                                             :model       (or (args "model") (:model defaults))
                                             :temperature 0.3
                                             :max-tokens  4096})
            max-iter    (min (or (args "max_iterations") 10) 50)
            result      (agent/ask db (args "question")
                                   {:invoke-fn      invoke-fn
                                    :repo-name      db-name
                                    :max-iterations max-iter
                                    :continue-from  (args "continue_from")})
            usage       (:usage result)
            answer      (:answer result)
            session-id  (:session-id result)]
        (log! "agent/done"
              (str "status=" (:status result)
                   " iterations=" (:iterations usage)
                   " tokens=" (+ (:input-tokens usage 0) (:output-tokens usage 0))))
        (if (= :budget-exhausted (:status result))
          (if answer
            (tool-result (str answer
                              "\n\n[Session " session-id " saved — to continue exploring, "
                              "call noumenon_ask with continue_from=\"" session-id "\"]"))
            (tool-error (str "Budget exhausted after " max-iter " iterations"
                             " (" (:input-tokens usage 0) " in / "
                             (:output-tokens usage 0) " out tokens) with no answer. "
                             "Try increasing max_iterations or narrowing the question.")))
          (tool-result (or answer
                           (str "No answer found (status: " (name (:status result)) ")"))))))))

(def ^:private valid-reanalyze-scopes
  #{"all" "prompt-changed" "model-changed" "stale"})

(defn- prepare-reanalysis!
  "Retract analysis attrs for files matching the reanalyze scope.
   Returns count of files marked for re-analysis, or nil if no scope given."
  [conn db reanalyze {:keys [prompt-hash model-id]}]
  (when reanalyze
    (let [scope (keyword reanalyze)
          files (analyze/files-for-reanalysis db scope {:prompt-hash prompt-hash
                                                        :model-id    model-id})
          paths (mapv :file/path files)
          n     (if (seq paths) (sync/retract-analysis! conn paths) 0)]
      (log! (str "Marked " n " file(s) for re-analysis (scope: " reanalyze ")"))
      n)))

(defn- handle-analyze [args defaults]
  (validate-llm-inputs! args)
  (let [reanalyze (args "reanalyze")]
    (when (and reanalyze (not (valid-reanalyze-scopes reanalyze)))
      (throw (ex-info (str "Invalid reanalyze scope: " reanalyze
                           ". Must be one of: all, prompt-changed, model-changed, stale")
                      {:scope reanalyze})))
    (with-conn args defaults
      (fn [{:keys [conn repo-path]}]
        (let [{:keys [prompt-fn model-id]}
              (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                                :model    (or (args "model") (:model defaults))})
              prompt-hash (analyze/prompt-hash (:template (analyze/load-prompt-template)))]
          (prepare-reanalysis! conn (d/db conn) reanalyze
                               {:prompt-hash prompt-hash :model-id model-id})
          (let [concurrency (min (or (args "concurrency") 3) 20)
                max-files   (args "max_files")
                result      (analyze/analyze-repo! conn repo-path prompt-fn
                                                   (cond-> {:model-id    model-id
                                                            :concurrency concurrency}
                                                     max-files (assoc :max-files max-files)))]
            (tool-result (str "Analysis complete. "
                              (:files-analyzed result 0) " files analyzed"
                              (when (pos? (:files-parse-errored result 0))
                                (str ", " (:files-parse-errored result 0) " parse errors"))
                              (when (pos? (:files-errored result 0))
                                (str ", " (:files-errored result 0) " errors"))
                              ". " (get-in result [:total-usage :input-tokens] 0)
                              " in / " (get-in result [:total-usage :output-tokens] 0) " out tokens"
                              (when-let [c (get-in result [:total-usage :cost-usd])]
                                (when (pos? c) (str " ($" (format "%.2f" c) ")")))))))))))

(defn- handle-enrich [args defaults]
  (with-conn args defaults
    (fn [{:keys [conn repo-path]}]
      (let [concurrency (min (or (args "concurrency") 8) 20)
            result      (imports/enrich-repo! conn repo-path
                                              {:concurrency concurrency})]
        (tool-result (str "Enrich complete. "
                          (:files-processed result 0) " files processed, "
                          (:imports-resolved result 0) " imports resolved."))))))

(defn- handle-list-databases [_args defaults]
  (let [db-dir (util/resolve-db-dir defaults)
        names  (db/list-db-dirs db-dir)]
    (if (seq names)
      (let [client (db/create-client db-dir)
            stats  (mapv #(db/db-stats client %) names)]
        (tool-result
         (str/join "\n"
                   (map (fn [{:keys [name commits files dirs cost error]}]
                          (if error
                            (str name " (error: " error ")")
                            (str name ": " commits " commits, " files " files, " dirs " dirs"
                                 (when (pos? cost) (str ", $" (format "%.2f" cost))))))
                        stats))))
      (tool-result "No databases found."))))

(defn- handle-benchmark-run [args defaults]
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [conn db repo-path]}]
      (let [{:keys [prompt-fn]}
            (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                              :model    (or (args "model") (:model defaults))})
            layers      (validate-layers (args "layers"))
            mode        (cond-> {}
                          layers (assoc :layers layers))
            result      (bench/run-benchmark! db repo-path prompt-fn
                                              :conn conn
                                              :mode mode
                                              :budget {:max-questions (args "max_questions")}
                                              :report? (args "report")
                                              :concurrency 3)]
        (tool-result
         (str "Benchmark complete. Run ID: " (:run-id result)
              "\nQuestions: " (get-in result [:aggregate :question-count])
              (when-let [fm (get-in result [:aggregate :full-mean])]
                (str "\nFull mean: " (format "%.1f%%" (* 100.0 (double fm)))))
              (when-let [rm (get-in result [:aggregate :raw-mean])]
                (str "\nRaw mean: " (format "%.1f%%" (* 100.0 (double rm)))))
              (when-let [rp (:report-path result)]
                (str "\nReport: " rp))))))))

(def ^:private max-run-id-len 256)

(defn- find-run
  "Find a benchmark run by ID, or the latest run if no ID given."
  [db run-id]
  (let [runs (if run-id
               (d/q '[:find (pull ?r [*]) :in $ ?id
                      :where [?r :bench.run/id ?id]]
                    db run-id)
               (d/q '[:find (pull ?r [*])
                      :where [?r :bench.run/id _]]
                    db))]
    (->> runs (map first) (sort-by :bench.run/started-at) last)))

(defn- format-run-summary
  "Format a benchmark run as a human-readable summary string."
  [run detail?]
  (let [base (str "Run: " (:bench.run/id run)
                  "\nStatus: " (name (:bench.run/status run))
                  "\nCommit: " (:bench.run/commit-sha run)
                  "\nQuestions: " (:bench.run/question-count run)
                  (when-let [fm (:bench.run/full-mean run)]
                    (str "\nFull mean: " (format "%.1f%%" (* 100.0 (double fm)))))
                  (when-let [rm (:bench.run/raw-mean run)]
                    (str "\nRaw mean: " (format "%.1f%%" (* 100.0 (double rm)))))
                  (when (:bench.run/canonical? run) "\nCanonical: true"))]
    (if-not detail?
      base
      (str base "\n\nPer-question results:\n"
           (str/join "\n"
                     (map (fn [r]
                            (str (name (:bench.result/question-id r))
                                 " " (name (or (:bench.result/category r) :unknown))
                                 " full=" (name (or (:bench.result/full-score r) :n/a))
                                 " raw=" (name (or (:bench.result/raw-score r) :n/a))))
                          (sort-by :bench.result/question-id
                                   (:bench.run/results run))))))))

(defn- handle-benchmark-results [args defaults]
  (when-let [rid (args "run_id")]
    (validate-string-length! "run_id" rid max-run-id-len))
  (with-conn args defaults
    (fn [{:keys [db]}]
      (if-let [run (find-run db (args "run_id"))]
        (tool-result (format-run-summary run (args "detail")))
        (tool-error "No benchmark runs found.")))))

(defn- handle-benchmark-compare [args defaults]
  (validate-string-length! "run_id_a" (args "run_id_a") max-run-id-len)
  (validate-string-length! "run_id_b" (args "run_id_b") max-run-id-len)
  (with-conn args defaults
    (fn [{:keys [db]}]
      (let [id-a (args "run_id_a")
            id-b (args "run_id_b")
            pull-run (fn [id]
                       (ffirst (d/q '[:find (pull ?r [*]) :in $ ?id
                                      :where [?r :bench.run/id ?id]]
                                    db id)))
            run-a (pull-run id-a)
            run-b (pull-run id-b)]
        (cond
          (not run-a) (tool-error (str "Run not found: " id-a))
          (not run-b) (tool-error (str "Run not found: " id-b))
          :else
          (let [{:keys [deltas]} (bench/compare-runs run-a run-b)]
            (tool-result
             (str "Comparing " id-a " vs " id-b "\n\n"
                  (str/join "\n"
                            (map (fn [[k v]]
                                   (str (name k) ": "
                                        (if (pos? v) "+" "")
                                        (format "%.1f" (* 100.0 v)) "pp"))
                                 (sort-by key deltas)))))))))))

(defn- format-digest-summary
  "Format digest pipeline results as a human-readable summary string."
  [r]
  (str "Digest complete."
       (when-let [u (:update r)]
         (str "\nUpdate: " (or (:added u) 0) " added, "
              (or (:modified u) 0) " modified."))
       (when-let [a (:analyze r)]
         (str "\nAnalyze: " (:files-analyzed a 0) " files analyzed"
              (when-let [c (get-in a [:total-usage :cost-usd])]
                (when (pos? c) (str " ($" (format "%.2f" c) ")")))))
       (when-let [b (:benchmark r)]
         (str "\nBenchmark: run-id=" (:run-id b)
              (when-let [fm (get-in b [:aggregate :full-mean])]
                (str ", full=" (format "%.1f%%" (* 100.0 (double fm)))))))))

(defn- handle-digest [args defaults]
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [conn repo-path]}]
      (let [{:keys [prompt-fn model-id]}
            (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                              :model    (or (args "model") (:model defaults))})
            repo-uri (.getCanonicalPath (java.io.File. (str repo-path)))
            results  (atom {})]
        ;; Import + Enrich
        (when-not (or (args "skip_import") (args "skip_enrich"))
          (let [r (sync/update-repo! conn repo-path repo-uri {:concurrency 8})]
            (swap! results assoc :update r)))
        ;; Analyze
        (when-not (args "skip_analyze")
          (let [r (analyze/analyze-repo! conn repo-path prompt-fn
                                         {:model-id model-id :concurrency 3})]
            (swap! results assoc :analyze r)))
        ;; Benchmark
        (when-not (args "skip_benchmark")
          (let [db     (d/db conn)
                layers (validate-layers (args "layers"))
                mode   (cond-> {} layers (assoc :layers layers))
                r      (bench/run-benchmark! db repo-path prompt-fn
                                             :conn conn :mode mode
                                             :budget {:max-questions (args "max_questions")}
                                             :report? (args "report")
                                             :concurrency 3)]
            (swap! results assoc :benchmark
                   (select-keys r [:run-id :aggregate :stop-reason :report-path]))))
        (tool-result (format-digest-summary @results))))))

(defn- handle-introspect [args defaults]
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [db db-name repo-path]}]
      (let [provider (or (args "provider") (:provider defaults))
            model    (or (args "model") (:model defaults))
            meta-conn (get-or-create-conn (util/resolve-db-dir defaults)
                                          "noumenon-internal")
            {:keys [invoke-fn]}
            (llm/make-messages-fn-from-opts
             {:provider provider :model model
              :temperature 0.7 :max-tokens 8192})
            invoke-fn-factory
            (fn []
              (:invoke-fn
               (llm/make-messages-fn-from-opts
                {:provider provider :model model
                 :temperature 0.0 :max-tokens 4096})))
            result (introspect/run-loop!
                    {:db                  db
                     :repo-name           db-name
                     :repo-path           repo-path
                     :meta-conn           meta-conn
                     :invoke-fn-factory   invoke-fn-factory
                     :optimizer-invoke-fn invoke-fn
                     :max-iterations      (or (args "max_iterations") 10)
                     :max-hours           (args "max_hours")
                     :max-cost            (args "max_cost")
                     :model-config        {:provider provider :model model}})]
        (tool-result (str "Introspect complete: " (:improvements result)
                          " improvements in " (:iterations result)
                          " iterations (final score: "
                          (format "%.3f" (:final-score result))
                          ", run-id: " (:run-id result) ")"))))))

(defn- handle-introspect-start [args defaults]
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [db db-name repo-path]}]
      (let [provider  (or (args "provider") (:provider defaults))
            model     (or (args "model") (:model defaults))
            meta-conn (get-or-create-conn (util/resolve-db-dir defaults)
                                          "noumenon-internal")
            stop-flag (atom false)
            {:keys [invoke-fn]}
            (llm/make-messages-fn-from-opts
             {:provider provider :model model :temperature 0.7 :max-tokens 8192})
            invoke-fn-factory
            (fn []
              (:invoke-fn
               (llm/make-messages-fn-from-opts
                {:provider provider :model model :temperature 0.0 :max-tokens 4096})))
            run-opts  {:db db :repo-name db-name :repo-path repo-path
                       :meta-conn meta-conn
                       :invoke-fn-factory invoke-fn-factory
                       :optimizer-invoke-fn invoke-fn
                       :max-iterations (or (args "max_iterations") 10)
                       :max-hours (args "max_hours")
                       :max-cost (args "max_cost")
                       :model-config {:provider provider :model model}
                       :stop-flag stop-flag}
            run-id    (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID))
            fut       (future
                        (try
                          (let [result (introspect/run-loop! (assoc run-opts :run-id run-id))]
                            (swap! introspect-sessions assoc-in [run-id :status] :completed)
                            (swap! introspect-sessions assoc-in [run-id :result] result)
                            result)
                          (catch Exception e
                            (swap! introspect-sessions assoc-in [run-id :status] :error)
                            (swap! introspect-sessions assoc-in [run-id :error] (.getMessage e)))))]
        (swap! introspect-sessions assoc run-id
               {:status :running :future fut :stop-flag stop-flag})
        (tool-result (str "Introspect started. Run ID: " run-id
                          "\nUse noumenon_introspect_status to check progress."))))))

(defn- handle-introspect-status [args _defaults]
  (let [run-id (args "run_id")]
    (if-let [session (get @introspect-sessions run-id)]
      (let [{:keys [status result error]} session]
        (tool-result
         (case status
           :running   (str "Status: running")
           :completed (str "Status: completed\n"
                           "Improvements: " (:improvements result)
                           " in " (:iterations result) " iterations"
                           "\nFinal score: " (format "%.3f" (:final-score result)))
           :stopped   (str "Status: stopped")
           :error     (str "Status: error\n" error))))
      (tool-error (str "Unknown run ID: " run-id)))))

(defn- handle-introspect-stop [args _defaults]
  (let [run-id (args "run_id")]
    (if-let [session (get @introspect-sessions run-id)]
      (do (reset! (:stop-flag session) true)
          (tool-result (str "Stop requested for " run-id
                            ". Will halt after current iteration.")))
      (tool-error (str "Unknown run ID: " run-id)))))

(defn- handle-introspect-history [args defaults]
  (let [query-name (args "query_name")]
    (validate-string-length! "query_name" query-name 256)
    (when-not (str/starts-with? (str query-name) "introspect-")
      (throw (ex-info "Only introspect-* queries are available"
                      {:user-message "Use one of: introspect-runs, introspect-improvements, introspect-by-target, introspect-score-trend, introspect-failed-approaches"})))
    (let [meta-conn (get-or-create-conn (util/resolve-db-dir defaults)
                                        "noumenon-internal")
          db        (d/db meta-conn)
          result    (query/run-named-query db query-name)]
      (if (:ok result)
        (let [rows  (take (min (or (some-> (args "limit") long) 100) 1000)
                          (:ok result))
              total (count (:ok result))]
          (tool-result (str (pr-str (vec rows))
                            (when (> total (count rows))
                              (str "\n;; Showing " (count rows)
                                   " of " total " results")))))
        (tool-error (str "Query error: " (:error result)))))))

(def ^:private tool-handlers
  {"noumenon_import"            handle-import
   "noumenon_status"            handle-status
   "noumenon_query"             handle-query
   "noumenon_list_queries"      handle-list-queries
   "noumenon_get_schema"        handle-get-schema
   "noumenon_update"            handle-update
   "noumenon_ask"               handle-ask
   "noumenon_analyze"           handle-analyze
   "noumenon_enrich"            handle-enrich
   "noumenon_list_databases"    handle-list-databases
   "noumenon_benchmark_run"     handle-benchmark-run
   "noumenon_benchmark_results" handle-benchmark-results
   "noumenon_benchmark_compare" handle-benchmark-compare
   "noumenon_digest"            handle-digest
   "noumenon_introspect"          handle-introspect
   "noumenon_introspect_start"   handle-introspect-start
   "noumenon_introspect_status"  handle-introspect-status
   "noumenon_introspect_stop"    handle-introspect-stop
   "noumenon_introspect_history" handle-introspect-history})

;; --- MCP method handlers ---

(defn- handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "noumenon" :version (util/read-version)}})

(defn- handle-tools-list [_params]
  {:tools tools})

(defn- handle-tools-call [params defaults]
  (let [tool-name (get params "name")
        arguments (or (get params "arguments") {})]
    (if-let [handler (tool-handlers tool-name)]
      (try
        (handler arguments defaults)
        (catch clojure.lang.ExceptionInfo e
          (log! "tool/error" tool-name (.getMessage e))
          (tool-error (or (:user-message (ex-data e))
                          (str "Internal error: " (.getMessage e)))))
        (catch Exception e
          (log! "tool/error" tool-name (.getMessage e))
          (tool-error "An unexpected internal error occurred.")))
      (tool-error (str "Unknown tool: " tool-name)))))

;; --- Main loop ---

(def ^:private max-line-bytes
  "Maximum allowed JSON-RPC line size (10 MB)."
  (* 10 1024 1024))

(defn- read-bounded-line
  "Read a line from reader, rejecting if it exceeds max-line-bytes. Returns nil on EOF."
  [^BufferedReader reader]
  (let [sb (StringBuilder.)]
    (loop []
      (let [ch (.read reader)]
        (cond
          (= ch -1)          (when (pos? (.length sb)) (.toString sb))
          (= ch (int \newline)) (.toString sb)
          (= ch (int \return))  (do (.mark reader 1)
                                    (when (not= (.read reader) (int \newline))
                                      (.reset reader))
                                    (.toString sb))
          :else
          (do (.append sb (char ch))
              (when (> (.length sb) max-line-bytes)
                (throw (ex-info "Request exceeds maximum size" {:limit max-line-bytes})))
              (recur)))))))

(defn- suppress-datomic-logging! []
  (.setLevel (java.util.logging.Logger/getLogger "datomic")
             java.util.logging.Level/WARNING))

(defn- dispatch-method
  "Dispatch a JSON-RPC method. Returns a JSON response string, or nil for notifications."
  [id method params defaults]
  (case method
    "initialize"              (format-response id (handle-initialize params))
    "notifications/initialized" nil
    "tools/list"              (format-response id (handle-tools-list params))
    "tools/call"              (format-response id (handle-tools-call params defaults))
    "ping"                    (format-response id {})
    "resources/list"          (format-response id {:resources []})
    (format-error id -32601 (str "Method not found: " method))))

(defn- process-line!
  "Parse one JSON-RPC line, dispatch, and write response."
  [line ^PrintWriter writer defaults]
  (try
    (let [request (json/read-str line)
          id      (get request "id")
          method  (get request "method")
          params  (or (get request "params") {})]
      (when-let [response (dispatch-method id method params defaults)]
        (.println writer response)))
    (catch Exception e
      (log! "parse/error" (.getMessage e))
      (.println writer (format-error nil -32700 "Parse error")))))

(defn serve!
  "Start MCP server. Reads JSON-RPC from stdin, writes responses to stdout.
   Blocks until stdin EOF. Options: :db-dir, :provider, :model."
  [opts]
  (suppress-datomic-logging!)
  (log! "noumenon MCP server starting")
  (let [reader   (BufferedReader. (io/reader System/in))
        writer   (PrintWriter. System/out true)
        defaults (cond-> (select-keys opts [:db-dir :provider :model])
                   (:no-auto-update opts) (assoc :auto-update false))]
    (log! "noumenon MCP server ready")
    (loop []
      (when-let [line (try
                        (read-bounded-line reader)
                        (catch Exception e
                          (log! "read/error" (.getMessage e))
                          (.println writer (format-error nil -32700 "Request too large"))
                          :oversized))]
        (when-not (= line :oversized)
          (when (seq line)
            (process-line! line writer defaults)))
        (recur)))))
