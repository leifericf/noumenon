(ns noumenon.mcp
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.db :as db]
            [noumenon.embed :as embed]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.benchmark :as bench]
            [noumenon.imports :as imports]
            [noumenon.introspect :as introspect]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.repo :as repo]
            [noumenon.sessions :as sessions]
            [noumenon.sync :as sync]
            [noumenon.synthesize :as synthesize]
            [noumenon.util :as util :refer [log!]])
  (:import [java.io BufferedReader PrintWriter]
           [java.lang ProcessHandle]))

;; --- Helpers ---

;; --- Connection cache ---

;; --- Async introspect sessions ---

(defn- get-or-create-conn
  "Delegate to shared db/conn-cache."
  [db-dir db-name]
  (db/get-or-create-conn db-dir db-name))

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
(def ^:private allowed-layers #{:raw :import :enrich :full :embedded})
(def ^:private allowed-introspect-targets #{:examples :system-prompt :rules :code :train})

(defn- validate-llm-inputs!
  "Validate model and provider string lengths when present."
  [args]
  (when-let [m (args "model")] (util/validate-string-length! "model" m max-model-len))
  (when-let [p (args "provider")] (util/validate-string-length! "provider" p max-provider-len)))

(defn- validate-layers
  "Parse and validate a comma-separated layers string. Returns keyword vector or nil."
  [layers-str]
  (when layers-str
    (util/validate-string-length! "layers" layers-str max-layers-len)
    (let [kws (mapv keyword (str/split layers-str #","))]
      (when-let [bad (seq (remove allowed-layers kws))]
        (throw (ex-info (str "Unknown layers: " (pr-str bad)
                             ". Valid: raw, import, enrich, full")
                        {:user-message (str "Unknown layers: " (pr-str bad)
                                            ". Valid: raw, import, enrich, full")})))
      kws)))

;; --- Tool definitions ---

(def ^:private repo-path-prop
  {"repo_path" {:type "string" :description "Repository path — filesystem path, Git URL, or Perforce depot path (//depot/...)"}})

;; db-dir-prop removed: db-dir is server-level config only

(def ^:private tools
  [{:name "noumenon_import"
    :description "Import a repository's commit history and file structure into the knowledge graph. Accepts Git repos, Git URLs, or Perforce depot paths (//depot/...). Safe to call on already-imported repositories — only processes new commits and files."
    :inputSchema {:type "object"
                  :properties repo-path-prop
                  :required ["repo_path"]}}
   {:name "noumenon_status"
    :description "RECOMMENDED FIRST STEP — call this before reading files. Returns entity counts (commits, files, directories) and the HEAD SHA of the last imported commit. Compare with `git rev-parse HEAD` to check if the knowledge graph is up to date."
    :inputSchema {:type "object"
                  :properties repo-path-prop
                  :required ["repo_path"]}}
   {:name "noumenon_query"
    :description "Use INSTEAD of Glob/Grep for codebase search — the knowledge graph knows file structure, dependencies, complexity, and commit history. Runs a named Datalog query. Some queries require params — use noumenon_list_queries to see available names and params."
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
    :description "List all available named Datalog queries with descriptions and required parameters. Use this to discover what structured questions you can ask the knowledge graph via noumenon_query."
    :inputSchema {:type "object" :properties {}}}
   {:name "noumenon_get_schema"
    :description "Get the database schema showing all attributes and their types. Requires a repo to have been imported first."
    :inputSchema {:type "object"
                  :properties repo-path-prop
                  :required ["repo_path"]}}
   {:name "noumenon_update"
    :description "Update the knowledge graph with latest changes. Runs import + enrich for changed files. For git-p4 clones, automatically syncs from Perforce first. Fast and cheap (no LLM calls by default). Pass analyze=true to also re-analyze changed files with LLM. Works as a first-time setup too — if no database exists, runs the full pipeline."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"analyze" {:type "boolean"
                                                 :description "Also run LLM analysis on changed files (default: false)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_ask"
    :description "Use for codebase exploration BEFORE reading files — ask any natural-language question about a repository. Uses an AI agent to run iterative Datalog queries against the knowledge graph. Requires prior import — if the repository has not been imported yet, call noumenon_update first. Uses LLM API calls. For structured queries, prefer noumenon_query."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"question" {:type "string" :description "Question to ask about the repository"}
                                      "provider" {:type "string" :description "LLM provider: glm, claude-api, or claude-cli (aliases: claude = claude-cli)"}
                                      "model" {:type "string" :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "max_iterations" {:type "integer" :description "Max query iterations (default: 10, max: 50)"}
                                      "continue_from" {:type "string" :description "Session ID from a budget-exhausted run — resumes the agent from where it left off"}})
                  :required ["question" "repo_path"]}}
   {:name "noumenon_search"
    :description "Fast semantic search over files and components — no LLM calls. Uses a TF-IDF vector index built from analyzed summaries. Returns ranked results by relevance score. Requires prior analyze + embed — both are included automatically when you run noumenon_digest. Much cheaper than noumenon_ask for simple 'find relevant files' queries."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"query" {:type "string" :description "Search query — natural language or keywords"}
                                      "limit" {:type "integer" :description "Max results to return (default: 8, max: 50)"}})
                  :required ["query" "repo_path"]}}
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
   {:name "noumenon_synthesize"
    :description "Identify architectural components from analyzed codebase data. Queries the knowledge graph for file summaries, import graph, and directory structure, then uses an LLM to identify components, classify files (layer, category, patterns, purpose), and map dependencies. Language-agnostic. Requires a prior analyze."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string"
                                                  :description "LLM provider: glm, claude-api, or claude-cli"}
                                      "model" {:type "string"
                                               :description "Model alias (e.g. sonnet, haiku, opus)"}})
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
    :description "Run the full Noumenon pipeline: import, enrich, analyze (LLM), synthesize, embed, and benchmark. WARNING: analyze, synthesize, and benchmark steps are expensive (LLM calls). Use skip_analyze, skip_synthesize, and skip_benchmark for a quick structural import. Each step is idempotent."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider"}
                                      "model" {:type "string" :description "Model alias"}
                                      "skip_import" {:type "boolean" :description "Skip the import+enrich step (either flag skips the combined step)"}
                                      "skip_enrich" {:type "boolean" :description "Skip the import+enrich step (either flag skips the combined step)"}
                                      "skip_analyze" {:type "boolean" :description "Skip analyze step"}
                                      "skip_synthesize" {:type "boolean" :description "Skip synthesize step"}
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
                                      "max_cost" {:type "number" :description "Stop when cost exceeds threshold (dollars)"}
                                      "target" {:type "string" :description "Comma-separated targets: examples, system-prompt, rules, code, train (default: all — LLM chooses)"}
                                      "eval_runs" {:type "integer" :description "Evaluation passes per iteration for median variance reduction (default: 1)"}
                                      "git_commit" {:type "boolean" :description "Git commit after each improvement"}})
                  :required ["repo_path"]}}
   {:name "noumenon_introspect_start"
    :description "Start an introspect run asynchronously in the background. Returns a run-id immediately. Use noumenon_introspect_status to check progress and noumenon_introspect_stop to halt."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider"}
                                      "model" {:type "string" :description "Model alias"}
                                      "max_iterations" {:type "integer" :description "Max iterations (default: 10)"}
                                      "max_hours" {:type "number" :description "Stop after N hours"}
                                      "max_cost" {:type "number" :description "Cost threshold"}
                                      "target" {:type "string" :description "Comma-separated targets: examples, system-prompt, rules, code, train (default: all — LLM chooses)"}
                                      "eval_runs" {:type "integer" :description "Evaluation passes per iteration for median variance reduction (default: 1)"}
                                      "git_commit" {:type "boolean" :description "Git commit after each improvement"}})
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
    :description "Query the introspect improvement history from the internal meta database."
    :inputSchema {:type "object"
                  :properties {"query_name" {:type "string"
                                             :description "Named query: introspect-runs (list all runs), introspect-improvements (accepted changes), introspect-by-target (grouped by target), introspect-score-trend (score over time), introspect-failed-approaches (rejected changes)"
                                             :enum ["introspect-runs" "introspect-improvements"
                                                    "introspect-by-target" "introspect-score-trend"
                                                    "introspect-failed-approaches"]}
                               "limit" {:type "integer"
                                        :description "Maximum result rows (default: 100)"}}
                  :required ["query_name"]}}
   {:name "noumenon_reseed"
    :description "Reseed prompts, queries, and rules from classpath EDN into the meta database. Use after editing seed files."
    :inputSchema {:type "object" :properties {}}}
   {:name "noumenon_artifact_history"
    :description "Show change history for a prompt or rules artifact. Returns timestamps and sources."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :description "Artifact type"
                                       :enum ["prompt" "rules"]}
                               "name" {:type "string"
                                       :description "Artifact name (required for prompt, ignored for rules)"}}
                  :required ["type"]}}])

;; --- Tool handlers ---

(defn- lookup-repo-uri
  "Look up stored :repo/uri for a database name. Returns path string or nil."
  [db-dir db-name]
  (let [db-path (io/file db-dir "noumenon" db-name)]
    (when (.isDirectory db-path)
      (try
        (let [conn (get-or-create-conn db-dir db-name)
              db   (d/db conn)]
          (ffirst (d/q '[:find ?uri :where [_ :repo/uri ?uri]] db)))
        (catch Exception e
          (log! "lookup-repo-uri" db-name (.getMessage e))
          nil)))))

(defn- with-conn
  "Resolve repo identifier from arguments, get/create connection, call f with context.
   Accepts filesystem paths, Git URLs, or database names.
   When auto-update is enabled (default), transparently updates stale databases before returning."
  [args defaults f]
  (let [raw-id (args "repo_path")]
    (when-not (string? raw-id)
      (throw (ex-info "repo_path is required" {:field "repo_path"})))
    (util/validate-string-length! "repo_path" raw-id max-repo-path-len)
    (let [db-dir    (util/resolve-db-dir defaults)
          {:keys [repo-path db-name]}
          (repo/resolve-repo raw-id db-dir {:lookup-uri-fn lookup-repo-uri})
          conn      (get-or-create-conn db-dir db-name)
          meta-conn (db/ensure-meta-db db-dir)]
      (when (and (:auto-update defaults true)
                 (not (str/starts-with? (str repo-path) "db://")))
        (try
          (let [db (d/db conn)]
            (when (sync/stale? db repo-path)
              (log! "auto-update" "HEAD changed, updating...")
              (sync/update-repo! conn repo-path repo-path {:concurrency 8})))
          (catch Exception e
            (log! "auto-update" (str "failed, continuing: " (.getMessage e))))))
      (f {:conn conn :db (d/db conn)
          :meta-conn meta-conn :meta-db (d/db meta-conn)
          :repo-path repo-path :db-dir db-dir :db-name db-name}))))

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
      (let [git-r   (git/import-commits! conn repo-path repo-path (:progress-fn defaults))
            files-r (files/import-files! conn repo-path repo-path)]
        (tool-result (format-import-summary git-r files-r))))))

(defn- handle-status [args defaults]
  (with-conn args defaults
    (fn [{:keys [db]}]
      (let [{:keys [commits files dirs head-sha]} (query/repo-stats db)
            head (if head-sha
                   (str "\nHead: " (subs head-sha 0 (min 7 (count head-sha))))
                   "")]
        (tool-result (str commits " commits, " files " files, " dirs " directories." head
                          "\n\nTip: Use noumenon_query or noumenon_ask to explore the codebase before reading files directly."))))))

(defn- handle-search [args defaults]
  (util/validate-string-length! "query" (args "query") max-question-len)
  (with-conn args defaults
    (fn [{:keys [db-dir db-name]}]
      (let [idx   (embed/get-cached-index db-dir db-name)
            _     (when-not idx
                    (throw (ex-info "No TF-IDF index found. Run embed (or digest) first."
                                    {:user-message "No TF-IDF index. Run: noumenon embed <repo>"})))
            limit (min (or (args "limit") 8) 50)
            results (embed/search idx (args "query") :limit limit)]
        (if (seq results)
          (tool-result
           (str "Found " (count results) " results:\n\n"
                (->> results
                     (map-indexed
                      (fn [i {:keys [kind path name text score]}]
                        (str (inc i) ". "
                             (if (= :file kind)
                               (str path " (file)")
                               (str name " (component)"))
                             " — score: " (format "%.3f" (double score))
                             "\n   " (first (clojure.string/split-lines text)))))
                     (clojure.string/join "\n"))))
          (tool-result "No results found. The index may be empty — run analyze + embed first."))))))

(defn- handle-query [args defaults]
  (util/validate-string-length! "query_name" (args "query_name") 256)
  (with-conn args defaults
    (fn [{:keys [db meta-db]}]
      (let [raw-params (args "params")
            _          (when (> (count raw-params) 20)
                         (throw (ex-info "Too many params"
                                         {:user-message "params: max 20 entries"})))
            _          (doseq [[k v] raw-params]
                         (util/validate-string-length! (str "params." k) (str v) 1024))
            params     (into {} (map (fn [[k v]] [(keyword k) v])) raw-params)
            result (query/run-named-query meta-db db (args "query_name") params)]
        (if (:ok result)
          (let [all-rows   (:ok result)
                total      (count all-rows)
                limit      (min (or (some-> (args "limit") long) 500) 10000)
                rows       (take limit all-rows)
                truncated? (> total limit)
                query-name (args "query_name")
                query-def  (artifacts/load-named-query meta-db query-name)
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

(defn- handle-list-queries [_args defaults]
  (let [db-dir    (util/resolve-db-dir defaults)
        meta-conn (db/ensure-meta-db db-dir)
        meta-db   (d/db meta-conn)
        lines     (->> (artifacts/list-active-query-names meta-db)
                       (keep (fn [n]
                               (when-let [q (artifacts/load-named-query meta-db n)]
                                 (str n " — " (:description q "no description")
                                      (when (seq (:inputs q))
                                        (str " [requires params: " (str/join ", " (map name (:inputs q))) "]")))))))]
    (tool-result
     (if (seq lines)
       (str "Available queries (pass the name to noumenon_query):\n"
            (str/join "\n" lines))
       "No named queries found. First call noumenon_update with your repo_path to initialize the knowledge graph, then retry. If queries are still missing, call noumenon_reseed."))))

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
  (util/validate-string-length! "repo_path" (args "repo_path") max-repo-path-len)
  (validate-llm-inputs! args)
  (let [db-dir    (util/resolve-db-dir defaults)
        {:keys [repo-path db-name]}
        (repo/resolve-repo (args "repo_path") db-dir {:lookup-uri-fn lookup-repo-uri})
        conn      (get-or-create-conn db-dir db-name)
        meta-conn (db/ensure-meta-db db-dir)
        analyze?  (args "analyze")
        opts      (if analyze?
                    (let [{:keys [prompt-fn model-id]}
                          (llm/wrap-as-prompt-fn-from-opts
                           {:provider (or (args "provider") (:provider defaults))
                            :model    (or (args "model") (:model defaults))})]
                      {:concurrency 8 :analyze? true
                       :meta-db (d/db meta-conn)
                       :model-id model-id :invoke-llm prompt-fn})
                    {:concurrency 8})
        result    (sync/update-repo! conn repo-path repo-path opts)]
    (tool-result (format-update-changes result))))

(defn- handle-ask [args defaults]
  (util/validate-string-length! "question" (args "question") max-question-len)
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [db meta-db db-dir db-name]}]
      (let [{:keys [invoke-fn]}
            (llm/make-messages-fn-from-opts {:provider    (or (args "provider") (:provider defaults))
                                             :model       (or (args "model") (:model defaults))
                                             :temperature 0.3
                                             :max-tokens  4096})
            max-iter    (min (or (args "max_iterations") 10) 50)
            eidx        (embed/get-cached-index db-dir db-name)
            result      (agent/ask meta-db db (args "question")
                                   {:invoke-fn      invoke-fn
                                    :repo-name      db-name
                                    :embed-index    eidx
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
                           "The agent completed without finding an answer. Try rephrasing the question or increasing max_iterations.")))))))

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
      (fn [{:keys [conn meta-db repo-path]}]
        (let [{:keys [prompt-fn model-id]}
              (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                                :model    (or (args "model") (:model defaults))})
              prompt-hash (analyze/prompt-hash (:template (analyze/load-prompt-template meta-db)))]
          (prepare-reanalysis! conn (d/db conn) reanalyze
                               {:prompt-hash prompt-hash :model-id model-id})
          (let [concurrency (min (or (args "concurrency") 3) 20)
                max-files   (args "max_files")
                result      (analyze/analyze-repo! conn repo-path prompt-fn
                                                   (cond-> {:meta-db     meta-db
                                                            :model-id    model-id
                                                            :concurrency concurrency
                                                            :progress-fn (:progress-fn defaults)}
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

(defn- handle-synthesize [args defaults]
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [conn meta-conn db-name]}]
      (artifacts/reseed! meta-conn)
      (let [meta-db   (d/db meta-conn)
            {:keys [prompt-fn model-id]}
            (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                              :model    (or (args "model") (:model defaults))})
            result (synthesize/synthesize-repo!
                    conn prompt-fn
                    {:meta-db meta-db :model-id model-id :repo-name db-name})]
        (tool-result (str "Synthesis complete. "
                          (:components result 0) " components identified, "
                          (:files-classified result 0) " files classified"
                          (when-let [m (:mode result)]
                            (str " (mode: " (name m)
                                 (when-let [p (:partitions result)] (str ", " p " partitions"))
                                 ")"))
                          (when-let [u (:usage result)]
                            (str " (" (:input-tokens u 0) " in / "
                                 (:output-tokens u 0) " out tokens)"))
                          (when-let [e (:error result)]
                            (str "\nError: " e))
                          "."))))))

(defn- format-pipeline-stages
  "Format pipeline stages as [import:3 analyze:42 enrich:1], or nil."
  [ops]
  (let [stages (keep (fn [op]
                       (when-let [n (ops op)]
                         (str (name op) ":" n)))
                     [:import :enrich :analyze :synthesize])]
    (when (seq stages)
      (str " [" (str/join " " stages) "]"))))

(defn- handle-list-databases [_args defaults]
  (let [db-dir (util/resolve-db-dir defaults)
        names  (db/list-db-dirs db-dir)]
    (if (seq names)
      (let [client (db/create-client db-dir)
            stats  (mapv #(db/db-stats client %) names)]
        (tool-result
         (str/join "\n"
                   (map (fn [{:keys [name commits files dirs cost ops error]}]
                          (if error
                            (str name " (error: " error ")")
                            (str name ": " commits " commits, " files " files, " dirs " dirs"
                                 (when (pos? cost) (str ", $" (format "%.2f" cost)))
                                 (format-pipeline-stages ops))))
                        stats))))
      (tool-result "No databases found."))))

(defn- handle-benchmark-run [args defaults]
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [conn meta-db db db-dir db-name repo-path]}]
      (let [{:keys [prompt-fn]}
            (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                              :model    (or (args "model") (:model defaults))})
            layers      (validate-layers (args "layers"))
            mode        (cond-> {}
                          layers (assoc :layers layers))
            model-cfg   {:provider (or (args "provider") (:provider defaults))
                         :model    (or (args "model") (:model defaults))}
            result      (bench/run-benchmark! db repo-path prompt-fn
                                              :meta-db meta-db
                                              :conn conn
                                              :mode mode
                                              :model-config model-cfg
                                              :budget {:max-questions (args "max_questions")}
                                              :report? (args "report")
                                              :progress-fn (:progress-fn defaults)
                                              :concurrency 3
                                              :db-dir db-dir :db-name db-name)]
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
    (util/validate-string-length! "run_id" rid max-run-id-len))
  (with-conn args defaults
    (fn [{:keys [db]}]
      (if-let [run (find-run db (args "run_id"))]
        (tool-result (format-run-summary run (args "detail")))
        (tool-error "No benchmark runs found.")))))

(defn- handle-benchmark-compare [args defaults]
  (util/validate-string-length! "run_id_a" (args "run_id_a") max-run-id-len)
  (util/validate-string-length! "run_id_b" (args "run_id_b") max-run-id-len)
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
    (fn [{:keys [conn meta-conn db-dir db-name repo-path]}]
      (artifacts/reseed! meta-conn)
      (let [meta-db (d/db meta-conn)
            {:keys [prompt-fn model-id]}
            (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                              :model    (or (args "model") (:model defaults))})
            repo-uri (if (str/starts-with? (str repo-path) "db://")
                       repo-path
                       (.getCanonicalPath (java.io.File. (str repo-path))))
            results  (atom {})]
        ;; Import + Enrich
        (when-not (or (args "skip_import") (args "skip_enrich"))
          (let [r (sync/update-repo! conn repo-path repo-uri {:concurrency 8})]
            (swap! results assoc :update r)))
        ;; Analyze
        (when-not (args "skip_analyze")
          (let [r (analyze/analyze-repo! conn repo-path prompt-fn
                                         {:meta-db meta-db :model-id model-id :concurrency 3
                                          :progress-fn (:progress-fn defaults)})]
            (swap! results assoc :analyze r)))
        ;; Synthesize
        (when-not (args "skip_synthesize")
          (try
            (let [synth-llm (llm/wrap-as-prompt-fn-from-opts
                             {:provider (or (args "provider") (:provider defaults))
                              :model    (or (args "model") (:model defaults))
                              :max-tokens 16384})
                  r (synthesize/synthesize-repo!
                     conn (:prompt-fn synth-llm)
                     {:meta-db meta-db :model-id (:model-id synth-llm) :repo-name db-name})]
              (swap! results assoc :synthesize r))
            (catch Exception e
              (log! "digest/synthesize" (str "skipped: " (.getMessage e))))))
        ;; Embed
        (let [db (d/db conn)]
          (try
            (let [idx (embed/build-index! db db-dir db-name)]
              (swap! results assoc :embed {:entries (count (:entries idx))}))
            (catch Exception e
              (log! "digest/embed" (str "skipped: " (.getMessage e))))))
        ;; Benchmark
        (when-not (args "skip_benchmark")
          (let [db     (d/db conn)
                layers (validate-layers (args "layers"))
                mode   (cond-> {} layers (assoc :layers layers))
                model-cfg {:provider (or (args "provider") (:provider defaults))
                           :model    (or (args "model") (:model defaults))}
                r      (bench/run-benchmark! db repo-path prompt-fn
                                             :meta-db meta-db
                                             :conn conn :mode mode
                                             :model-config model-cfg
                                             :budget {:max-questions (args "max_questions")}
                                             :report? (args "report")
                                             :concurrency 3
                                             :progress-fn (:progress-fn defaults)
                                             :db-dir db-dir :db-name db-name)]
            (swap! results assoc :benchmark
                   (select-keys r [:run-id :aggregate :stop-reason :report-path]))))
        (tool-result (format-digest-summary @results))))))

(defn- handle-introspect [args defaults]
  (validate-llm-inputs! args)
  (with-conn args defaults
    (fn [{:keys [db meta-conn db-name repo-path]}]
      (let [provider (or (args "provider") (:provider defaults))
            model    (or (args "model") (:model defaults))
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
                    (cond-> {:db                  db
                             :repo-name           db-name
                             :repo-path           repo-path
                             :meta-conn           meta-conn
                             :invoke-fn-factory   invoke-fn-factory
                             :optimizer-invoke-fn invoke-fn
                             :max-iterations      (or (args "max_iterations") 10)
                             :max-hours           (args "max_hours")
                             :max-cost            (args "max_cost")
                             :eval-runs           (or (args "eval_runs") 1)
                             :git-commit?         (and (args "git_commit")
                                                       (not (:read-only defaults)))
                             :model-config        {:provider provider :model model}
                             :progress-fn         (:progress-fn defaults)}
                      (args "target")
                      (assoc :allowed-targets
                             (set (map keyword (str/split (args "target") #","))))))]
        (tool-result (str "Introspect complete: " (:improvements result)
                          " improvements in " (:iterations result)
                          " iterations (final score: "
                          (format "%.3f" (:final-score result))
                          ", run-id: " (:run-id result) ")"))))))

(defn- handle-introspect-start [args defaults]
  (validate-llm-inputs! args)
  (sessions/evict-stale!)
  (when (>= (sessions/running-count) sessions/max-sessions)
    (throw (ex-info "Too many active introspect sessions"
                    {:user-message (str "Maximum " sessions/max-sessions
                                        " concurrent sessions. Stop one first.")})))
  (with-conn args defaults
    (fn [{:keys [db meta-conn db-name repo-path]}]
      (let [provider  (or (args "provider") (:provider defaults))
            model     (or (args "model") (:model defaults))
            stop-flag (atom false)
            {:keys [invoke-fn]}
            (llm/make-messages-fn-from-opts
             {:provider provider :model model :temperature 0.7 :max-tokens 8192})
            invoke-fn-factory
            (fn []
              (:invoke-fn
               (llm/make-messages-fn-from-opts
                {:provider provider :model model :temperature 0.0 :max-tokens 4096})))
            run-opts  (cond-> {:db db :repo-name db-name :repo-path repo-path
                               :meta-conn meta-conn
                               :invoke-fn-factory invoke-fn-factory
                               :optimizer-invoke-fn invoke-fn
                               :max-iterations (or (args "max_iterations") 10)
                               :max-hours (args "max_hours")
                               :max-cost (args "max_cost")
                               :eval-runs (or (args "eval_runs") 1)
                               :git-commit? (args "git_commit")
                               :model-config {:provider provider :model model}
                               :stop-flag stop-flag}
                        (args "target")
                        (assoc :allowed-targets
                               (->> (str/split (args "target") #",")
                                    (keep (comp allowed-introspect-targets keyword str/trim))
                                    set)))
            run-id    (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID))
            now       (System/currentTimeMillis)]
        (when (>= (sessions/running-count) sessions/max-sessions)
          (throw (ex-info "Too many active introspect sessions"
                          {:user-message (str "Maximum " sessions/max-sessions
                                              " concurrent sessions. Stop one first.")})))
        (sessions/register! run-id {:status :running :stop-flag stop-flag :started-at now})
        (let [fut (future
                    (try
                      (let [result (introspect/run-loop! (assoc run-opts :run-id run-id))
                            final-status (if @stop-flag :stopped :completed)]
                        (sessions/update-session! run-id
                                                  #(merge % {:status final-status :result result
                                                             :completed-at (System/currentTimeMillis)}))
                        result)
                      (catch Exception e
                        (sessions/update-session! run-id
                                                  #(merge % {:status :error :error (.getMessage e)
                                                             :completed-at (System/currentTimeMillis)})))))]
          (sessions/update-session! run-id #(assoc % :future fut))
          (tool-result (str "Introspect started. Run ID: " run-id
                            "\nUse noumenon_introspect_status to check progress.")))))))

(defn- handle-introspect-status [args _defaults]
  (let [run-id (args "run_id")]
    (util/validate-string-length! "run_id" run-id max-run-id-len)
    (if-let [session (sessions/get-session run-id)]
      (let [{:keys [status result error started-at]} session]
        (tool-result
         (case status
           :running   (let [elapsed-min (quot (- (System/currentTimeMillis) (or started-at 0))
                                              60000)]
                        (str "Status: running\nElapsed: " elapsed-min " minutes"))
           :completed (str "Status: completed\n" (sessions/format-result-summary result))
           :stopped   (str "Status: stopped (by request)\n"
                           (when result (sessions/format-result-summary result)))
           :error     (str "Status: error\n" error))))
      (tool-error (str "Unknown run ID: " run-id)))))

(defn- handle-introspect-stop [args _defaults]
  (let [run-id (args "run_id")]
    (util/validate-string-length! "run_id" run-id max-run-id-len)
    (if-let [session (sessions/get-session run-id)]
      (case (:status session)
        :running   (do (reset! (:stop-flag session) true)
                       (tool-result (str "Stop requested for " run-id
                                         ". Will halt after current iteration.")))
        :completed (tool-result (str "Run " run-id " already completed."))
        :stopped   (tool-result (str "Run " run-id " already stopped."))
        :error     (tool-result (str "Run " run-id " already terminated with an error.")))
      (tool-error (str "Unknown run ID: " run-id)))))

(defn- handle-introspect-history [args defaults]
  (let [query-name (args "query_name")]
    (util/validate-string-length! "query_name" query-name 256)
    (when-not (str/starts-with? (str query-name) "introspect-")
      (throw (ex-info "Only introspect-* queries are available"
                      {:user-message "Use one of: introspect-runs, introspect-improvements, introspect-by-target, introspect-score-trend, introspect-failed-approaches"})))
    (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir defaults))
          meta-db   (d/db meta-conn)
          query-def (artifacts/load-named-query meta-db query-name)
          result    (query/run-named-query meta-db meta-db query-name)]
      (if (:ok result)
        (let [rows  (take (min (or (some-> (args "limit") long) 100) 1000)
                          (:ok result))
              total (count (:ok result))
              header (str "Query '" query-name "': " (count rows)
                          (when (> total (count rows)) (str " of " total))
                          " results"
                          (when-let [cols (:columns query-def)]
                            (str "\nColumns: " (str/join ", " cols)))
                          "\n")]
          (tool-result (str header
                            (str/join "\n" (map pr-str rows))
                            (when (> total (count rows))
                              (str "\n... truncated (" (- total (count rows)) " more rows)")))))
        (tool-error (str "Query error: " (:error result)))))))

(defn- handle-reseed [_args defaults]
  (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir defaults))]
    (artifacts/reseed! meta-conn)
    (let [meta-db (d/db meta-conn)]
      (tool-result (str "Reseeded: " (count (artifacts/list-active-query-names meta-db))
                        " queries, rules, and prompts.")))))

(defn- handle-artifact-history [args defaults]
  (let [atype (args "type")
        aname (args "name")]
    (when-not (#{"prompt" "rules"} atype)
      (throw (ex-info "Invalid type" {:user-message "type must be 'prompt' or 'rules'"})))
    (when (and (= atype "prompt") (nil? aname))
      (throw (ex-info "Missing name"
                      {:user-message "name is required when type is 'prompt'. Provide the prompt name to look up."})))
    (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir defaults))
          history   (case atype
                      "prompt" (do (util/validate-string-length! "name" aname 256)
                                   (artifacts/prompt-history meta-conn aname))
                      "rules"  (artifacts/rules-history meta-conn))]
      (tool-result
       (if (seq history)
         (->> history
              (map (fn [{:keys [tx-time source]}]
                     (str tx-time " [" (name source) "]")))
              (str/join "\n"))
         "No history found.")))))

(def ^:private tool-handlers
  {"noumenon_import"            handle-import
   "noumenon_status"            handle-status
   "noumenon_query"             handle-query
   "noumenon_list_queries"      handle-list-queries
   "noumenon_get_schema"        handle-get-schema
   "noumenon_update"            handle-update
   "noumenon_ask"               handle-ask
   "noumenon_search"            handle-search
   "noumenon_analyze"           handle-analyze
   "noumenon_enrich"            handle-enrich
   "noumenon_synthesize"        handle-synthesize
   "noumenon_list_databases"    handle-list-databases
   "noumenon_benchmark_run"     handle-benchmark-run
   "noumenon_benchmark_results" handle-benchmark-results
   "noumenon_benchmark_compare" handle-benchmark-compare
   "noumenon_digest"            handle-digest
   "noumenon_introspect"          handle-introspect
   "noumenon_introspect_start"   handle-introspect-start
   "noumenon_introspect_status"  handle-introspect-status
   "noumenon_introspect_stop"    handle-introspect-stop
   "noumenon_introspect_history" handle-introspect-history
   "noumenon_reseed"             handle-reseed
   "noumenon_artifact_history"   handle-artifact-history})

;; --- MCP method handlers ---

(defn- handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "noumenon" :version (util/read-version)}})

(defn- handle-tools-list [_params]
  {:tools tools})

(defn- make-mcp-progress-fn
  "Create a progress-fn that sends MCP notifications/progress to the writer.
   Returns nil if no progressToken was provided by the client."
  [^PrintWriter writer progress-token]
  (when progress-token
    (fn [{:keys [current total message]}]
      (let [notification (json/write-str
                          {:jsonrpc "2.0"
                           :method  "notifications/progress"
                           :params  {:progressToken progress-token
                                     :progress      current
                                     :total         (or total 0)
                                     :message       message}})]
        (locking writer
          (.println writer notification))))))

(defn- handle-tools-call [params defaults ^PrintWriter writer]
  (let [tool-name (get params "name")
        arguments (or (get params "arguments") {})
        meta-info (get params "_meta")
        progress-token (get meta-info "progressToken")
        progress-fn (make-mcp-progress-fn writer progress-token)]
    (if-let [handler (tool-handlers tool-name)]
      (try
        (handler arguments (if progress-fn
                             (assoc defaults :progress-fn progress-fn)
                             defaults))
        (catch clojure.lang.ExceptionInfo e
          (let [msg (.getMessage e)
                user-msg (:user-message (ex-data e))]
            (log! "tool/error" tool-name msg)
            (tool-error (or user-msg
                            (str "Internal error: " msg)))))
        (catch Exception e
          (let [msg (.getMessage e)]
            (log! "tool/error" tool-name msg)
            (tool-error
             (if (and msg (str/includes? msg ".lock"))
               (str "FATAL: Database locked by another process (likely a running daemon). "
                    "DO NOT RETRY — all Noumenon tool calls will fail until the lock is released. "
                    "Tell the user to kill the daemon: ps aux | grep 'noumenon.*daemon' | grep -v grep | awk '{print $2}' | xargs kill")
               (str "Internal error in " tool-name ": " msg
                    ". DO NOT RETRY — report this error to the user."))))))
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

;; --- Remote proxy mode ---

(defn- load-connection-config
  "Read the active connection from ~/.noumenon/config.edn."
  []
  (let [config-path (str (io/file (System/getProperty "user.home") ".noumenon" "config.edn"))]
    (when (.exists (io/file config-path))
      (let [config (edn/read-string {:readers {}} (slurp config-path))
            active (:active config)]
        (when (and active (not= active "local"))
          (get-in config [:connections active]))))))

(defn- detect-local-daemon
  "Check ~/.noumenon/daemon.edn for a running local daemon.
   Returns a proxy connection map if the daemon PID is alive, nil otherwise."
  []
  (let [daemon-file (io/file (System/getProperty "user.home") ".noumenon" "daemon.edn")]
    (when (.exists daemon-file)
      (try
        (let [{:keys [port pid]} (edn/read-string {:readers {}} (slurp daemon-file))
              handle (.orElse (ProcessHandle/of pid) nil)]
          (when (and handle (.isAlive handle) port)
            {:host (str "http://127.0.0.1:" port)}))
        (catch Exception _ nil)))))

(defn- repo-path->db-name
  "Translate a local repo_path to a database name for remote queries.
   Tries: git remote origin URL -> org-repo, else basename."
  [repo-path]
  (when (and repo-path (not (str/blank? repo-path)))
    (let [f (io/file repo-path)]
      (if (.isDirectory f)
        ;; Try to get origin URL and derive name
        (let [{:keys [exit out]} (shell/sh
                                  "git" "-C" repo-path
                                  "remote" "get-url" "origin")]
          (if (zero? exit)
            ;; Use the same logic as repo-manager/url->db-name
            (let [url (str/trim out)
                  cleaned (-> url (str/replace #"\.git$" "") (str/replace #"/$" ""))
                  parts   (str/split cleaned #"[/:]")
                  segments (take-last 2 parts)]
              (str/replace (str/join "-" segments) #"[^a-zA-Z0-9\-_.]" ""))
            ;; No remote, fall back to basename
            (.getName f)))
        ;; Not a directory — might already be a db-name
        repo-path))))

(def ^:private tool->api-path
  "Map MCP tool names to HTTP API paths and methods."
  {"noumenon_import"            {:path "/api/import" :method :post}
   "noumenon_status"            {:path "/api/status/:repo" :method :get}
   "noumenon_query"             {:path "/api/query" :method :post}
   "noumenon_list_queries"      {:path "/api/queries" :method :get}
   "noumenon_get_schema"        {:path "/api/schema/:repo" :method :get}
   "noumenon_update"            {:path "/api/update" :method :post}
   "noumenon_ask"               {:path "/api/ask" :method :post}
   "noumenon_search"            {:path "/api/search" :method :post}
   "noumenon_analyze"           {:path "/api/analyze" :method :post}
   "noumenon_enrich"            {:path "/api/enrich" :method :post}
   "noumenon_synthesize"        {:path "/api/synthesize" :method :post}
   "noumenon_list_databases"    {:path "/api/databases" :method :get}
   "noumenon_benchmark_run"     {:path "/api/benchmark" :method :post}
   "noumenon_benchmark_results" {:path "/api/benchmark/results" :method :get}
   "noumenon_benchmark_compare" {:path "/api/benchmark/compare" :method :get}
   "noumenon_digest"            {:path "/api/digest" :method :post}
   "noumenon_introspect"        {:path "/api/introspect" :method :post}
   "noumenon_introspect_start"  {:path "/api/introspect" :method :post}
   "noumenon_introspect_status" {:path "/api/introspect/status" :method :get}
   "noumenon_introspect_stop"   {:path "/api/introspect/stop" :method :post}
   "noumenon_introspect_history" {:path "/api/introspect/history" :method :get}
   "noumenon_reseed"            {:path "/api/reseed" :method :post}
   "noumenon_artifact_history"  {:path "/api/artifacts/history" :method :get}})

(def ^:private read-only-proxy-tools
  "Tools safe to proxy without admin privileges."
  #{"noumenon_status" "noumenon_query" "noumenon_list_queries"
    "noumenon_get_schema" "noumenon_list_databases" "noumenon_ask"
    "noumenon_search" "noumenon_benchmark_results" "noumenon_benchmark_compare"
    "noumenon_introspect_status" "noumenon_introspect_history"
    "noumenon_artifact_history"})

(defn- proxy-tool-call
  "Forward a tool call to the remote HTTP API."
  [tool-name arguments remote-conn]
  (let [{:keys [path method]} (tool->api-path tool-name)
        {:keys [host token]} remote-conn]
    (when-not path
      (throw (ex-info (str "Unknown tool: " tool-name) {})))
    (when-not (read-only-proxy-tools tool-name)
      (log! (str "proxy: forwarding admin tool " tool-name " to remote")))
    (let [;; Translate repo_path to db-name
          db-name   (repo-path->db-name (get arguments "repo_path"))
          base-url  (if (or (str/starts-with? host "http://")
                            (str/starts-with? host "https://"))
                      host
                      (str "https://" host))
          _         (git/validate-proxy-host! base-url)
          ;; Replace :repo placeholder in path
          url-path  (str/replace path ":repo"
                                 (java.net.URLEncoder/encode (or db-name "") "UTF-8"))
          url       (str base-url url-path)
          ;; Replace repo_path with db-name in arguments
          args      (if db-name
                      (assoc arguments "repo_path" db-name)
                      arguments)]
      (try
        (let [;; Pass auth header via stdin to avoid token in process list
              curl-config (str (when token
                                 (str "header = \"Authorization: Bearer " token "\"\n"))
                               "header = \"Content-Type: application/json\"\n")
              ;; For GET, append non-empty args as query params
              get-url (if (and (= method :get) (seq args))
                        (str url "?" (str/join "&"
                                               (map (fn [[k v]]
                                                      (str (java.net.URLEncoder/encode (str k) "UTF-8")
                                                           "="
                                                           (java.net.URLEncoder/encode (str v) "UTF-8")))
                                                    args)))
                        url)
              resp (case method
                     :get  (shell/sh
                            "curl" "-s" "--config" "-" "-X" "GET" get-url
                            :in curl-config)
                     :post (shell/sh
                            "curl" "-s" "--config" "-" "-X" "POST" url
                            "-d" (json/write-str args)
                            :in curl-config))
              body (json/read-str (:out resp))]
          (if (get body "ok")
            (tool-result (json/write-str (get body "data")))
            (let [error-msg (or (get body "error") "Remote request failed")
                  status    (get body "status")]
              (tool-error (cond
                            (= status 401) "Authentication failed. Run `noum connect <url> --token <new-token>` to update credentials."
                            (= status 403) "Permission denied. This operation requires admin access."
                            :else error-msg)))))
        (catch Exception e
          (tool-error (str "Remote proxy error: " (.getMessage e))))))))

(defn- handle-tools-call-proxy
  "Route tools/call to either local handler or remote proxy."
  [params defaults ^PrintWriter writer remote-conn]
  (if remote-conn
    (let [tool-name (get params "name")
          arguments (or (get params "arguments") {})]
      (proxy-tool-call tool-name arguments remote-conn))
    (handle-tools-call params defaults writer)))

(defn serve!
  "Start MCP server. Reads JSON-RPC from stdin, writes responses to stdout.
   Blocks until stdin EOF. Options: :db-dir, :provider, :model."
  [opts]
  (suppress-datomic-logging!)
  (log! "noumenon MCP server starting")
  (let [reader      (BufferedReader. (io/reader System/in))
        writer      (PrintWriter. System/out true)
        remote-conn (or (load-connection-config) (detect-local-daemon))
        defaults    (cond-> (select-keys opts [:db-dir :provider :model])
                      (:no-auto-update opts) (assoc :auto-update false))
        dispatch    (fn [id method params]
                      (case method
                        "initialize"              (format-response id (handle-initialize params))
                        "notifications/initialized" nil
                        "tools/list"              (format-response id (handle-tools-list params))
                        "tools/call"              (format-response id (handle-tools-call-proxy
                                                                       params defaults writer remote-conn))
                        "ping"                    (format-response id {})
                        "resources/list"          (format-response id {:resources []})
                        (format-error id -32601 (str "Method not found: " method))))]
    (when remote-conn
      (log! (str "MCP proxy mode: forwarding to " (:host remote-conn)
                 (when-not (load-connection-config) " (auto-detected local daemon)"))))
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
            (try
              (let [request (json/read-str line)
                    id      (get request "id")
                    method  (get request "method")
                    params  (or (get request "params") {})]
                (when-let [response (dispatch id method params)]
                  (locking writer
                    (.println writer response))))
              (catch Exception e
                (log! "process/error" (.getMessage e))
                (.println writer (format-error nil -32700 "Parse error"))))))
        (recur)))))
