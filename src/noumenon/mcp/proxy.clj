(ns noumenon.mcp.proxy
  "Remote-proxy mode for the MCP server. When the local CLI is configured
   to point at a hosted daemon (or an auto-detected local daemon is
   running), tool calls are forwarded over HTTP instead of executed
   in-process."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [noumenon.git :as git]
            [noumenon.mcp.protocol :as protocol]
            [noumenon.util :as util :refer [log!]])
  (:import [java.lang ProcessHandle]))

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

(defn proxy-tool-call
  "Forward a tool call to the remote HTTP API."
  [tool-name arguments remote-conn]
  (let [{:keys [path method]} (tool->api-path tool-name)
        {:keys [host token]} remote-conn]
    (when-not path
      (throw (ex-info (str "Unknown tool: " tool-name) {})))
    (when-not (read-only-proxy-tools tool-name)
      (log! (str "proxy: forwarding admin tool " tool-name " to remote")))
    (let [db-name   (repo-path->db-name (get arguments "repo_path"))
          base-url  (if (or (str/starts-with? host "http://")
                            (str/starts-with? host "https://"))
                      host
                      (str "https://" host))
          _         (git/validate-proxy-host! base-url)
          url-path  (str/replace path ":repo"
                                 (java.net.URLEncoder/encode (or db-name "") "UTF-8"))
          url       (str base-url url-path)
          args      (if db-name
                      (assoc arguments "repo_path" db-name)
                      arguments)]
      (try
        (let [curl-config (str (when token
                                 (str "header = \"Authorization: Bearer " token "\"\n"))
                               "header = \"Content-Type: application/json\"\n")
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
                            :in curl-config))]
          (cond
            (not (zero? (:exit resp)))
            (protocol/tool-error
             (str "Cannot reach daemon at " host ". "
                  (when-let [err (not-empty (str/trim (or (:err resp) "")))]
                    (str "(" err ") "))
                  "Start it with `noum daemon`, or update "
                  "~/.noumenon/config.edn to point at a running host."))
            (str/blank? (:out resp))
            (protocol/tool-error
             (str "Empty response from daemon at " host ". "
                  "The daemon may be misconfigured or returning no body."))
            :else
            (let [body (json/read-str (:out resp))]
              (if (get body "ok")
                (protocol/tool-result (json/write-str (get body "data")))
                (let [error-msg (or (get body "error") "Remote request failed")
                      status    (get body "status")]
                  (protocol/tool-error (cond
                                         (= status 401) "Authentication failed. Run `noum connect <url> --token <new-token>` to update credentials."
                                         (= status 403) "Permission denied. This operation requires admin access."
                                         :else error-msg)))))))
        (catch Exception e
          (protocol/tool-error (str "Remote proxy error: " (.getMessage e))))))))

(defn resolve-conn
  "Pick the active proxy connection: explicit config first, then auto-detected daemon."
  []
  (or (load-connection-config) (detect-local-daemon)))
