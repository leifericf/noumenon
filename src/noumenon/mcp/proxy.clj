(ns noumenon.mcp.proxy
  "Remote-proxy mode for the MCP server. When the local CLI is configured
   to point at a hosted daemon (or an auto-detected local daemon is
   running), tool calls are forwarded over HTTP instead of executed
   in-process."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.git :as git]
            [noumenon.mcp.protocol :as protocol]
            [noumenon.util :as util :refer [log!]]
            [org.httpkit.client :as http])
  (:import [java.lang ProcessHandle]
           [java.net URLEncoder]))

(defn load-connection-config
  "Read the active connection from ~/.noumenon/config.edn."
  []
  (let [config-path (str (io/file (System/getProperty "user.home") ".noumenon" "config.edn"))]
    (when (.exists (io/file config-path))
      (let [config (edn/read-string {:readers {}} (slurp config-path))
            active (:active config)]
        (when (and active (not= active "local"))
          (get-in config [:connections active]))))))

(defn detect-local-daemon
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
   For directories, mirrors local CLI behavior via util/derive-db-name.
   For non-directories, assumes input may already be a db-name."
  [repo-path]
  (when (and repo-path (not (str/blank? repo-path)))
    (let [f (io/file repo-path)]
      (if (.isDirectory f)
        (util/derive-db-name (.getCanonicalPath f))
        repo-path))))

(def ^:private tool->api-path
  "Map MCP tool names to HTTP API paths and methods."
  {"noumenon_import"            {:path "/api/import" :method :post}
   "noumenon_status"            {:path "/api/status/:repo" :method :get}
   "noumenon_query"             {:path "/api/query" :method :post}
   "noumenon_query_federated"   {:path "/api/query-federated" :method :post}
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
  #{"noumenon_status" "noumenon_query" "noumenon_query_federated" "noumenon_list_queries"
    "noumenon_get_schema" "noumenon_list_databases" "noumenon_ask"
    "noumenon_search" "noumenon_benchmark_results" "noumenon_benchmark_compare"
    "noumenon_introspect_status" "noumenon_introspect_history"
    "noumenon_artifact_history"})

(defn- url-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- query-string [args]
  (str/join "&" (map (fn [[k v]] (str (url-encode k) "=" (url-encode v))) args)))

(defn- normalize-host
  "Prepend https:// to a bare host that lacks a scheme."
  [host]
  (if (or (str/starts-with? host "http://")
          (str/starts-with? host "https://"))
    host
    (str "https://" host)))

(defn- build-request-map
  "Pure: assemble the http-kit request map for `tool-name` against `remote-conn`."
  [tool-name arguments remote-conn]
  (let [{:keys [path method]} (tool->api-path tool-name)]
    (when-not path
      (throw (ex-info (str "Unknown tool: " tool-name) {})))
    (let [{:keys [host token]} remote-conn
          db-name   (repo-path->db-name (get arguments "repo_path"))
          base-url  (normalize-host host)
          _         (git/validate-proxy-host! base-url)
          url-path  (str/replace path ":repo" (url-encode (or db-name "")))
          base-args (cond-> arguments db-name (assoc "repo_path" db-name))
          headers   (cond-> {"Content-Type" "application/json"}
                      token (assoc "Authorization" (str "Bearer " token)))
          base      {:url     (str base-url url-path)
                     :method  method
                     :headers headers
                     :timeout 300000}]
      (case method
        :get  (assoc base :url (cond-> (:url base)
                                 (seq base-args) (str "?" (query-string base-args))))
        :post (assoc base :body (json/write-str base-args))))))

(defn- interpret-response
  "Pure: turn an http-kit response into a tool-result/tool-error map.
   The HTTP status code is taken from the response top level (`:status`),
   not from the JSON body — the daemon's `error-response` builds bodies
   as `{:ok false :error msg}` without echoing the code."
  [{:keys [error status body]} host]
  (cond
    error
    (protocol/tool-error
     (str "Cannot reach daemon at " host ". "
          (when-let [msg (some-> ^Exception error .getMessage str/trim not-empty)]
            (str "(" msg ") "))
          "Start it with `noum daemon`, or update "
          "~/.noumenon/config.edn to point at a running host."))

    (str/blank? body)
    (protocol/tool-error
     (str "Empty response from daemon at " host ". "
          "The daemon may be misconfigured or returning no body."))

    :else
    (let [parsed (json/read-str body)]
      (if (get parsed "ok")
        (protocol/tool-result (json/write-str (get parsed "data")))
        (let [error-msg (or (get parsed "error") "Remote request failed")]
          (protocol/tool-error
           (case status
             401 "Authentication failed. Run `noum connect <url> --token <new-token>` to update credentials."
             403 "Permission denied. This operation requires admin access."
             error-msg)))))))

(defn proxy-tool-call
  "Forward a tool call to the remote HTTP API via http-kit."
  [tool-name arguments remote-conn]
  (when-not (read-only-proxy-tools tool-name)
    (log! (str "proxy: forwarding admin tool " tool-name " to remote")))
  (try
    (let [req (build-request-map tool-name arguments remote-conn)]
      (interpret-response @(http/request req) (:host remote-conn)))
    (catch Exception e
      (protocol/tool-error (str "Remote proxy error: " (.getMessage e))))))

(defn resolve-conn
  "Pick the active proxy connection: explicit config first, then auto-detected daemon."
  []
  (or (load-connection-config) (detect-local-daemon)))
