(ns noumenon.mcp
  "Top-level MCP server. Holds the declarative tool schema, the
   tool-name → handler dispatch, and the JSON-RPC `serve!` entry point.
   Protocol framing lives in noumenon.mcp.protocol; remote-proxy mode
   lives in noumenon.mcp.proxy; per-cluster tool handlers live under
   noumenon.mcp.handlers.*."
  (:require [clojure.string :as str]
            [noumenon.mcp.handlers.benchmark :as h-bench]
            [noumenon.mcp.handlers.introspect :as h-intro]
            [noumenon.mcp.handlers.meta :as h-meta]
            [noumenon.mcp.handlers.mutation :as h-mut]
            [noumenon.mcp.handlers.query :as h-query]
            [noumenon.mcp.protocol :as protocol]
            [noumenon.mcp.proxy :as proxy]
            [noumenon.mcp.util :as mu]
            [noumenon.util :refer [log!]]))

;; --- Tool definitions ---

(def ^:private repo-path-prop
  {"repo_path" {:type "string" :description "Repository path — filesystem path, Git URL, or Perforce depot path (//depot/...)"}})

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
   {:name "noumenon_query_federated"
    :description "Run a named Datalog query merged across the trunk database and a local delta database for a developer's branch. Materializes the delta DB on demand from (repo_path, basis_sha), then returns trunk rows minus delta paths concatenated with delta's own rows so the result reflects the working branch. The query must be flagged :federation-safe? in its EDN — non-safe queries return trunk-only with federation_safe=false. Use this when a developer's HEAD diverges from the hosted trunk basis."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"query_name" {:type "string"
                                                    :description "Named query — must be federation-safe (see noumenon_list_queries)"}
                                      "basis_sha" {:type "string"
                                                   :description "40-char lowercase hex SHA — the trunk basis the delta is based on"}
                                      "branch" {:type "string"
                                                :description "Branch name (defaults to current local HEAD branch)"}
                                      "params" {:type "object"
                                                :description "Optional parameters for parameterized queries (string keys and values)"
                                                :additionalProperties {:type "string"}}
                                      "limit" {:type "integer"
                                               :description "Maximum number of result rows (default 500, max 10000)"}})
                  :required ["query_name" "repo_path" "basis_sha"]}}
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
                                                 :description "Also run LLM analysis on changed files (default: false)"}
                                      "path" {:type "string" :description "File/dir selector (comma-separated)"}
                                      "include" {:type "string" :description "Glob include selector (comma-separated)"}
                                      "exclude" {:type "string" :description "Glob exclude selector (comma-separated)"}
                                      "lang" {:type "string" :description "Language selector (comma-separated)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_ask"
    :description "Use for codebase exploration BEFORE reading files — ask any natural-language question about a repository. Uses an AI agent to run iterative Datalog queries against the knowledge graph. Requires prior import — if the repository has not been imported yet, call noumenon_update first. Uses LLM API calls. For structured queries, prefer noumenon_query."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"question" {:type "string" :description "Question to ask about the repository"}
                                      "provider" {:type "string" :description "LLM provider: glm or claude-api (alias: claude)"}
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
    :description "Run LLM analysis on repository files to enrich the knowledge graph with semantic metadata. By default only analyzes files not yet analyzed. Pass reanalyze to re-analyze files: all, prompt-changed, model-changed, or stale. Each file is checked against the content-addressed promotion cache before any LLM call: if a previously-analyzed file held the same blob-sha under the current prompt+model, its analysis is copied across with :prov/promoted-from lineage. The result reports files_analyzed, files_promoted, files_skipped. Pass no_promote=true to bypass the cache and always call the LLM. Requires a prior import."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string"
                                                  :description "LLM provider: glm or claude-api (alias: claude)"}
                                      "model" {:type "string"
                                               :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "concurrency" {:type "integer"
                                                     :description "Number of concurrent LLM calls (default: 3, max: 20)"}
                                      "max_files" {:type "integer"
                                                   :description "Stop after analyzing N files (useful for sampling)"}
                                      "reanalyze" {:type "string"
                                                   :description "Re-analyze scope: all, prompt-changed, model-changed, stale (default: only unanalyzed files)"}
                                      "no_promote" {:type "boolean"
                                                    :description "Bypass the content-addressed promotion cache; always invoke the LLM"}
                                      "path" {:type "string" :description "File/dir selector (comma-separated)"}
                                      "include" {:type "string" :description "Glob include selector (comma-separated)"}
                                      "exclude" {:type "string" :description "Glob exclude selector (comma-separated)"}
                                      "lang" {:type "string" :description "Language selector (comma-separated)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_enrich"
    :description "Extract cross-file import graph deterministically. No LLM calls — uses language-specific parsers. Requires a prior import."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"concurrency" {:type "integer"
                                                     :description "Extraction concurrency (default: 8, max: 20)"}
                                      "path" {:type "string" :description "File/dir selector (comma-separated)"}
                                      "include" {:type "string" :description "Glob include selector (comma-separated)"}
                                      "exclude" {:type "string" :description "Glob exclude selector (comma-separated)"}
                                      "lang" {:type "string" :description "Language selector (comma-separated)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_synthesize"
    :description "Identify architectural components from analyzed codebase data. Queries the knowledge graph for file summaries, import graph, and directory structure, then uses an LLM to identify components, classify files (layer, category, patterns, purpose), and map dependencies. Language-agnostic. Requires a prior analyze."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string"
                                                  :description "LLM provider: glm or claude-api"}
                                      "model" {:type "string"
                                               :description "Model alias (e.g. sonnet, haiku, opus)"}})
                  :required ["repo_path"]}}
   {:name "noumenon_list_databases"
    :description "List all noumenon databases with entity counts, pipeline stages, and cost."
    :inputSchema {:type "object" :properties {}}}
   {:name "noumenon_llm_providers"
    :description "List configured LLM providers, their available models, and defaults. Uses NOUMENON_LLM_PROVIDERS_EDN and NOUMENON_DEFAULT_PROVIDER."
    :inputSchema {:type "object" :properties {}}}
   {:name "noumenon_llm_models"
    :description "List available models for a provider. Tries provider API first and falls back to configured :models."
    :inputSchema {:type "object"
                  :properties {"provider" {:type "string" :description "Provider name (optional; defaults to configured default provider)"}}}}
   {:name "noumenon_benchmark_run"
    :description "Run a benchmark comparing LLM answers across knowledge graph layers. WARNING: Expensive — uses many LLM calls. Use max_questions to limit scope."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider: glm or claude-api"}
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
                                      "path" {:type "string" :description "File/dir selector (comma-separated)"}
                                      "include" {:type "string" :description "Glob include selector (comma-separated)"}
                                      "exclude" {:type "string" :description "Glob exclude selector (comma-separated)"}
                                      "lang" {:type "string" :description "Language selector (comma-separated)"}
                                      "max_questions" {:type "integer" :description "Benchmark: limit to N questions"}
                                      "layers" {:type "string" :description "Benchmark layers: raw,import,enrich,full (default: raw,full)"}
                                      "report" {:type "boolean" :description "Generate Markdown benchmark report"}})
                  :required ["repo_path"]}}
   {:name "noumenon_introspect"
    :description "Run an autonomous self-improvement loop: propose prompt changes, evaluate via benchmark, keep improvements. WARNING: Expensive — runs multiple benchmark evaluations. Use max_iterations to limit scope."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string" :description "LLM provider: glm or claude-api"}
                                      "model" {:type "string" :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "max_iterations" {:type "integer" :description "Max improvement iterations (default: 10)"}
                                      "max_hours" {:type "number" :description "Stop after N hours of wall-clock time"}
                                      "max_cost" {:type "number" :description "Stop when cost exceeds threshold (dollars)"}
                                      "target" {:type "string" :description "Comma-separated targets: examples, system-prompt, rules, code, train (default: all — LLM chooses)"}
                                      "eval_runs" {:type "integer" :description "Evaluation passes per iteration for median variance reduction (default: 1)"}
                                      "git_commit" {:type "boolean" :description "Git commit after each improvement"}
                                      "extra_repos" {:type "string" :description "Comma-separated extra repo paths/names for multi-repo evaluation (reduces overfitting)"}})
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
                                      "git_commit" {:type "boolean" :description "Git commit after each improvement"}
                                      "extra_repos" {:type "string" :description "Comma-separated extra repo paths/names for multi-repo evaluation (reduces overfitting)"}})
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
                                             :description "Named query: introspect-runs (list all runs), introspect-improvements (accepted changes), introspect-by-target (grouped by target), introspect-score-trend (score over time), introspect-failed-approaches (rejected changes), introspect-skipped (parse failures, validation errors)"
                                             :enum ["introspect-runs" "introspect-improvements"
                                                    "introspect-by-target" "introspect-score-trend"
                                                    "introspect-failed-approaches" "introspect-skipped"]}
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

;; --- Tool dispatch ---

(def ^:private tool-handlers
  {"noumenon_import"             h-mut/handle-import
   "noumenon_status"             h-query/handle-status
   "noumenon_query"              h-query/handle-query
   "noumenon_query_federated"    h-query/handle-query-federated
   "noumenon_list_queries"       h-query/handle-list-queries
   "noumenon_get_schema"         h-query/handle-get-schema
   "noumenon_update"             h-mut/handle-update
   "noumenon_ask"                h-mut/handle-ask
   "noumenon_search"             h-query/handle-search
   "noumenon_analyze"            h-mut/handle-analyze
   "noumenon_enrich"             h-mut/handle-enrich
   "noumenon_synthesize"         h-mut/handle-synthesize
   "noumenon_digest"             h-mut/handle-digest
   "noumenon_list_databases"     h-meta/handle-list-databases
   "noumenon_llm_providers"      h-meta/handle-llm-providers
   "noumenon_llm_models"         h-meta/handle-llm-models
   "noumenon_reseed"             h-meta/handle-reseed
   "noumenon_artifact_history"   h-meta/handle-artifact-history
   "noumenon_benchmark_run"      h-bench/handle-benchmark-run
   "noumenon_benchmark_results"  h-bench/handle-benchmark-results
   "noumenon_benchmark_compare"  h-bench/handle-benchmark-compare
   "noumenon_introspect"         h-intro/handle-introspect
   "noumenon_introspect_start"   h-intro/handle-introspect-start
   "noumenon_introspect_status"  h-intro/handle-introspect-status
   "noumenon_introspect_stop"    h-intro/handle-introspect-stop
   "noumenon_introspect_history" h-intro/handle-introspect-history})

(defn- handle-tools-call [params defaults ^java.io.PrintWriter writer]
  (let [tool-name      (get params "name")
        arguments      (or (get params "arguments") {})
        meta-info      (get params "_meta")
        progress-token (get meta-info "progressToken")
        progress-fn    (protocol/make-mcp-progress-fn writer progress-token)]
    (if-let [handler (tool-handlers tool-name)]
      (try
        (handler arguments (if progress-fn
                             (assoc defaults :progress-fn progress-fn)
                             defaults))
        (catch clojure.lang.ExceptionInfo e
          (let [msg (.getMessage e)
                user-msg (:user-message (ex-data e))]
            (log! "tool/error" tool-name msg)
            (mu/tool-error (or user-msg
                               (str "Internal error: " msg)))))
        (catch Exception e
          (let [msg (.getMessage e)]
            (log! "tool/error" tool-name msg)
            (mu/tool-error
             (if (and msg (str/includes? msg ".lock"))
               (str "FATAL: Database locked by another process (likely a running daemon). "
                    "DO NOT RETRY — all Noumenon tool calls will fail until the lock is released. "
                    "Tell the user to kill the daemon: ps aux | grep 'noumenon.*daemon' | grep -v grep | awk '{print $2}' | xargs kill")
               (str "Internal error in " tool-name ": " msg
                    ". DO NOT RETRY — report this error to the user."))))))
      (mu/tool-error (str "Unknown tool: " tool-name)))))

(defn- handle-tools-call-proxy
  "Route tools/call to either local handler or remote proxy."
  [params defaults ^java.io.PrintWriter writer remote-conn]
  (if remote-conn
    (let [tool-name (get params "name")
          arguments (or (get params "arguments") {})]
      (proxy/proxy-tool-call tool-name arguments remote-conn))
    (handle-tools-call params defaults writer)))

(defn serve!
  "Start MCP server. Reads JSON-RPC from stdin, writes responses to stdout.
   Blocks until stdin EOF. Options: :db-dir, :provider, :model."
  [opts]
  (protocol/suppress-datomic-logging!)
  (log! "noumenon MCP server starting")
  (let [{:keys [reader writer]} (protocol/open-stdio)
        defaults (cond-> (select-keys opts [:db-dir :provider :model])
                   (:no-auto-update opts) (assoc :auto-update false))
        dispatch (fn [id method params writer]
                   (case method
                     "initialize"                (protocol/format-response id (protocol/handle-initialize params))
                     "notifications/initialized" nil
                     "tools/list"                (protocol/format-response id (protocol/handle-tools-list tools))
                     "tools/call"                (protocol/format-response id (handle-tools-call-proxy
                                                                               params defaults writer (proxy/resolve-conn)))
                     "ping"                      (protocol/format-response id {})
                     "resources/list"            (protocol/format-response id {:resources []})
                     (protocol/format-error id -32601 (str "Method not found: " method))))]
    (when-let [conn (proxy/resolve-conn)]
      (log! (str "MCP proxy mode: forwarding to " (:host conn)
                 (when-not (proxy/load-connection-config) " (auto-detected local daemon)"))))
    (log! "noumenon MCP server ready")
    (protocol/run-stdio! {:reader reader :writer writer
                          :log-fn log!  :dispatch dispatch})))
