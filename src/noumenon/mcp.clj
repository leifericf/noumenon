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

(defn- validate-string-length!
  "Throw ex-info if s exceeds max-len characters."
  [field-name s max-len]
  (when (> (count s) max-len)
    (throw (ex-info (str field-name " exceeds maximum length of " max-len " characters")
                    {:field field-name :length (count s) :max max-len}))))

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
    :description "Get entity counts (commits, files, directories) for an imported repository"
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
                                                :additionalProperties {:type "string"}}})
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
    :description "Ask a question about a repository using AI-powered iterative Datalog querying"
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"question" {:type "string" :description "Question to ask about the repository"}
                                      "provider" {:type "string" :description "LLM provider: glm, claude-api, or claude-cli (aliases: claude = claude-cli)"}
                                      "model" {:type "string" :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "max_iterations" {:type "integer" :description "Max query iterations (default: 10, max: 50)"}})
                  :required ["question" "repo_path"]}}
   {:name "noumenon_analyze"
    :description "Run LLM analysis on repository files to enrich the knowledge graph with semantic metadata. Only analyzes files not yet analyzed. Requires a prior import."
    :inputSchema {:type "object"
                  :properties (merge repo-path-prop
                                     {"provider" {:type "string"
                                                  :description "LLM provider: glm, claude-api, or claude-cli (aliases: claude = claude-cli)"}
                                      "model" {:type "string"
                                               :description "Model alias (e.g. sonnet, haiku, opus)"}
                                      "concurrency" {:type "integer"
                                                     :description "Number of concurrent LLM calls (default: 3, max: 20)"}
                                      "max_files" {:type "integer"
                                                   :description "Stop after analyzing N files (useful for sampling)"}})
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
                  :required ["repo_path" "run_id_a" "run_id_b"]}}])

;; --- Tool handlers ---

(defn- with-conn
  "Resolve db-dir and db-name from arguments, get/create connection, call f with conn and db.
   When auto-update is enabled (default), transparently updates stale databases before returning."
  [args defaults f]
  (let [raw-path  (args "repo_path")
        _         (validate-string-length! "repo_path" raw-path max-repo-path-len)
        repo-path (.getCanonicalPath (io/file raw-path))
        _         (validate-repo-path! repo-path)
        db-dir  (util/resolve-db-dir defaults)
        db-name (util/derive-db-name repo-path)
        conn    (get-or-create-conn db-dir db-name)
        _       (when (:auto-update defaults true)
                  (let [db (d/db conn)]
                    (when (sync/stale? db repo-path)
                      (log! "auto-update" "HEAD changed, updating...")
                      (let [repo-uri (.getCanonicalPath (java.io.File. (str repo-path)))]
                        (sync/update-repo! conn repo-path repo-uri {:concurrency 8})))))
        db      (d/db conn)]
    (f {:conn conn :db db :repo-path repo-path :db-name db-name})))

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
      (let [{:keys [commits files dirs]} (query/repo-stats db)]
        (tool-result (str commits " commits, " files " files, " dirs " directories."))))))

(defn- handle-query [args defaults]
  (with-conn args defaults
    (fn [{:keys [db]}]
      (let [params (args "params")
            result (query/run-named-query db (args "query_name") params)]
        (if (:ok result)
          (let [rows       (:ok result)
                query-name (args "query_name")
                query-def  (query/load-named-query query-name)
                header     (when-let [cols (:columns query-def)]
                             (str "Columns: " (str/join ", " cols) "\n"))
                summary    (str "Query '" query-name "': " (count rows) " results\n")]
            (tool-result (str summary header (pr-str rows))))
          (tool-error (:error result)))))))

(defn- handle-list-queries [_args _defaults]
  (->> (query/list-query-names)
       (keep (fn [n]
               (when-let [q (query/load-named-query n)]
                 (str n " — " (:description q "no description")))))
       (str/join "\n")
       tool-result))

(defn- handle-get-schema [args defaults]
  (with-conn args defaults
    (fn [{:keys [db]}]
      (tool-result (query/schema-summary db)))))

(defn- handle-update [args defaults]
  (let [repo-path (args "repo_path")]
    (validate-repo-path! repo-path)
    (let [db-dir   (util/resolve-db-dir defaults)
          db-name  (util/derive-db-name repo-path)
          conn     (get-or-create-conn db-dir db-name)
          repo-uri (.getCanonicalPath (java.io.File. (str repo-path)))
          analyze? (args "analyze")
          opts     (cond-> {:concurrency 8}
                     analyze?
                     (assoc :analyze? true
                            :model-id (llm/model-alias->id
                                       (or (:model defaults) llm/default-model-alias))
                            :invoke-llm (llm/make-prompt-fn
                                         (llm/make-invoke-fn
                                          (llm/provider->kw
                                           (or (:provider defaults) llm/default-provider))
                                          {:model (llm/model-alias->id
                                                   (or (:model defaults) llm/default-model-alias))}))))
          result   (sync/update-repo! conn repo-path repo-uri opts)]
      (tool-result (pr-str result)))))

(defn- handle-ask [args defaults]
  (validate-string-length! "question" (args "question") max-question-len)
  (with-conn args defaults
    (fn [{:keys [db db-name]}]
      (let [provider-kw (llm/provider->kw (or (args "provider") (:provider defaults) llm/default-provider))
            model-id    (llm/model-alias->id (or (args "model") (:model defaults) llm/default-model-alias))
            invoke-fn   (llm/make-invoke-fn provider-kw {:model       model-id
                                                         :temperature 0.3
                                                         :max-tokens  4096})
            max-iter    (min (or (args "max_iterations") 10) 50)
            result      (agent/ask db (args "question")
                                   {:invoke-fn      invoke-fn
                                    :repo-name      db-name
                                    :max-iterations max-iter})
            usage       (:usage result)]
        (log! "agent/done"
              (str "status=" (:status result)
                   " iterations=" (:iterations usage)
                   " tokens=" (+ (:input-tokens usage 0) (:output-tokens usage 0))))
        (tool-result (or (:answer result)
                         (str "No answer found (status: " (name (:status result)) ")")))))))

(defn- handle-analyze [args defaults]
  (with-conn args defaults
    (fn [{:keys [conn repo-path]}]
      (let [provider-kw (llm/provider->kw (or (args "provider") (:provider defaults) llm/default-provider))
            model-id    (llm/model-alias->id (or (args "model") (:model defaults) llm/default-model-alias))
            invoke-llm  (llm/make-prompt-fn
                         (llm/make-invoke-fn provider-kw {:model model-id}))
            concurrency (min (or (args "concurrency") 3) 20)
            max-files   (args "max_files")
            result      (analyze/analyze-repo! conn repo-path invoke-llm
                                               (cond-> {:model-id    model-id
                                                        :concurrency concurrency}
                                                 max-files (assoc :max-files max-files)))]
        (tool-result (pr-str result))))))

(defn- handle-enrich [args defaults]
  (with-conn args defaults
    (fn [{:keys [conn repo-path]}]
      (let [concurrency (min (or (args "concurrency") 8) 20)
            result      (imports/enrich-repo! conn repo-path
                                              {:concurrency concurrency})]
        (tool-result (pr-str result))))))

(defn- handle-list-databases [_args defaults]
  (let [db-dir (util/resolve-db-dir defaults)
        names  (db/list-db-dirs db-dir)]
    (if (seq names)
      (tool-result (pr-str (mapv #(db/db-stats db-dir %) names)))
      (tool-result "No databases found."))))

(defn- handle-benchmark-run [args defaults]
  (with-conn args defaults
    (fn [{:keys [conn db repo-path]}]
      (let [provider-kw (llm/provider->kw (or (args "provider") (:provider defaults) llm/default-provider))
            model-id    (llm/model-alias->id (or (args "model") (:model defaults) llm/default-model-alias))
            invoke-llm  (llm/make-prompt-fn
                         (llm/make-invoke-fn provider-kw {:model model-id}))
            layers-str  (args "layers")
            layers      (when layers-str (mapv keyword (str/split layers-str #",")))
            mode        (cond-> {}
                          layers (assoc :layers layers))
            result      (bench/run-benchmark! db repo-path invoke-llm
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

(defn- handle-benchmark-results [args defaults]
  (with-conn args defaults
    (fn [{:keys [db]}]
      (let [run-id (args "run_id")
            detail? (args "detail")
            ;; Find the run — latest or by ID
            runs (if run-id
                   (d/q '[:find (pull ?r [*]) :in $ ?id
                          :where [?r :bench.run/id ?id]]
                        db run-id)
                   (d/q '[:find (pull ?r [*])
                          :where [?r :bench.run/id _]]
                        db))
            run  (->> runs (map first) (sort-by :bench.run/started-at) last)]
        (if-not run
          (tool-error "No benchmark runs found.")
          (let [base (str "Run: " (:bench.run/id run)
                          "\nStatus: " (name (:bench.run/status run))
                          "\nCommit: " (:bench.run/commit-sha run)
                          "\nQuestions: " (:bench.run/question-count run)
                          (when-let [fm (:bench.run/full-mean run)]
                            (str "\nFull mean: " (format "%.1f%%" (* 100.0 (double fm)))))
                          (when-let [rm (:bench.run/raw-mean run)]
                            (str "\nRaw mean: " (format "%.1f%%" (* 100.0 (double rm)))))
                          (when (:bench.run/canonical? run) "\nCanonical: true"))
                detail (when detail?
                         (let [results (:bench.run/results run)]
                           (str "\n\nPer-question results:\n"
                                (str/join "\n"
                                          (map (fn [r]
                                                 (str (name (:bench.result/question-id r))
                                                      " " (name (or (:bench.result/category r) :unknown))
                                                      " full=" (name (or (:bench.result/full-score r) :n/a))
                                                      " raw=" (name (or (:bench.result/raw-score r) :n/a))))
                                               (sort-by :bench.result/question-id results))))))]
            (tool-result (str base detail))))))))

(defn- handle-benchmark-compare [args defaults]
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
   "noumenon_benchmark_compare" handle-benchmark-compare})

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
                          "Internal error — check server logs.")))
        (catch Exception e
          (log! "tool/error" tool-name (.getMessage e))
          (tool-error "Internal error — check server logs.")))
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
            (try
              (let [request (json/read-str line)
                    id      (get request "id")
                    method  (get request "method")
                    params  (or (get request "params") {})]
                (case method
                  "initialize"
                  (.println writer (format-response id (handle-initialize params)))

                  "notifications/initialized"
                  nil ;; notification, no response

                  "tools/list"
                  (.println writer (format-response id (handle-tools-list params)))

                  "tools/call"
                  (.println writer (format-response id (handle-tools-call params defaults)))

                  "ping"
                  (.println writer (format-response id {}))

                  "resources/list"
                  (.println writer (format-response id {:resources []}))

                  ;; unknown method
                  (.println writer (format-error id -32601 (str "Method not found: " method)))))
              (catch Exception e
                (log! "parse/error" (.getMessage e))
                (.println writer (format-error nil -32700 "Parse error"))))))
        (recur)))))
