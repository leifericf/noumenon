(ns noumenon.mcp
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.agent :as agent]
            [noumenon.db :as db]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]])
  (:import [java.io BufferedReader PrintWriter]))

;; --- Helpers ---

(defn- derive-db-name
  "Derive a unique db name from a canonical repo path.
   Uses the basename plus a short hash of the full path to avoid collisions."
  [canonical-path]
  (let [basename    (-> canonical-path (str/replace #"/+$" "") (str/split #"/") last)
        hash-suffix (-> canonical-path hash Math/abs (Integer/toString 36))]
    (str basename "-" hash-suffix)))

(defn- validate-repo-path!
  "Validate that repo-path exists, is a directory, and contains .git.
   Throws ex-info with a generic message (details logged to stderr only)."
  [repo-path]
  (let [f      (io/file repo-path)
        reason (cond
                 (not (.exists f))            "does not exist"
                 (not (.isDirectory f))       "not a directory"
                 (not (.exists (io/file f ".git"))) "not a git repository")]
    (when reason
      (log! "validate-repo-path" reason repo-path)
      (throw (ex-info "Invalid or inaccessible repository path"
                      {:repo-path    repo-path
                       :user-message "Invalid or inaccessible repository path."})))))

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
   {:name "noumenon_schema"
    :description "Get the database schema showing all attributes and their types. Requires a repo to have been imported first."
    :inputSchema {:type "object"
                  :properties repo-path-prop
                  :required ["repo_path"]}}
   {:name "noumenon_sync"
    :description "Sync the knowledge graph with the latest git state. Runs import + postprocess for changed files. Fast and cheap (no LLM calls by default). Pass analyze=true to also re-analyze changed files with LLM. Works as a first-time setup too — if no database exists, runs the full pipeline."
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
                  :required ["question" "repo_path"]}}])

;; --- Tool handlers ---

(defn- with-conn
  "Resolve db-dir and db-name from arguments, get/create connection, call f with conn and db.
   When auto-sync is enabled (default), transparently syncs stale databases before returning."
  [args defaults f]
  (let [raw-path  (args "repo_path")
        _         (validate-string-length! "repo_path" raw-path max-repo-path-len)
        repo-path (.getCanonicalPath (io/file raw-path))
        _         (validate-repo-path! repo-path)
        db-dir    (or (:db-dir defaults)
                      (str (.getAbsolutePath (io/file "data" "datomic"))))
        db-name (derive-db-name repo-path)
        conn    (get-or-create-conn db-dir db-name)
        _       (when (:auto-sync defaults true)
                  (let [db (d/db conn)]
                    (when (sync/stale? db repo-path)
                      (log! "auto-sync" "HEAD changed, syncing...")
                      (let [repo-uri (.getCanonicalPath (java.io.File. (str repo-path)))]
                        (sync/sync-repo! conn repo-path repo-uri {:concurrency 8})))))
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
      (let [commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
            files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
            dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))]
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

(defn- handle-schema [args defaults]
  (with-conn args defaults
    (fn [{:keys [db]}]
      (tool-result (query/schema-summary db)))))

(defn- handle-sync [args defaults]
  (let [repo-path (args "repo_path")]
    (validate-repo-path! repo-path)
    (let [db-dir   (or (:db-dir defaults)
                       (str (.getAbsolutePath (io/file "data" "datomic"))))
          db-name  (derive-db-name repo-path)
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
          result   (sync/sync-repo! conn repo-path repo-uri opts)]
      (tool-result (pr-str result)))))

(defn- handle-ask [args defaults]
  (validate-string-length! "question" (args "question") max-question-len)
  (with-conn args defaults
    (fn [{:keys [db db-name]}]
      (let [provider-kw (llm/provider->kw (or (args "provider") (:provider defaults) llm/default-provider))
            model-id    (llm/model-alias->id (or (args "model") (:model defaults) llm/default-model-alias))
            invoke-fn   (llm/make-invoke-fn provider-kw {:model model-id})
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

(def ^:private tool-handlers
  {"noumenon_import"       handle-import
   "noumenon_status"       handle-status
   "noumenon_query"        handle-query
   "noumenon_list_queries" handle-list-queries
   "noumenon_schema"       handle-schema
   "noumenon_sync"         handle-sync
   "noumenon_ask"          handle-ask})

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
                   (:no-auto-sync opts) (assoc :auto-sync false))]
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
