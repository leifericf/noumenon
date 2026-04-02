(ns noum.main
  "Entry point for the noum launcher."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noum.cli :as cli]
            [noum.daemon :as daemon]
            [noum.demo :as demo]
            [noum.jar :as jar]
            [noum.jre :as jre]
            [noum.paths :as paths]
            [noum.setup :as setup]
            [noum.tui.confirm :as confirm]
            [noum.tui.core :as tui]
            [noum.tui.progress :as progress]
            [noum.tui.spinner :as spinner]
            [noum.tui.style :as style]))

(def ^:private version
  (or (try (:version (edn/read-string (slurp (io/resource "version.edn"))))
           (catch Exception _ nil))
      "dev"))

;; --- Config ---

(defn- load-config []
  (if (fs/exists? paths/config-path)
    (do (paths/ensure-private! paths/config-path)
        (edn/read-string (slurp paths/config-path)))
    {}))

;; --- HTTP client ---

(defn- private-address?
  "True if the host resolves to a private, loopback, or link-local IP."
  [host-str]
  (try
    (let [host (first (str/split host-str #":"))
          addr (java.net.InetAddress/getByName host)]
      (or (.isLoopbackAddress addr)
          (.isLinkLocalAddress addr)
          (.isSiteLocalAddress addr)))
    (catch Exception _ false)))

(defn- base-url
  "Build base URL from connection info. Supports remote --host.
   Remote hosts default to https:// unless --insecure is set.
   Rejects hosts that resolve to private/link-local addresses (SSRF protection)."
  [{:keys [port host insecure]}]
  (or (when host
        (when (and (not insecure)
                   (not (re-find #"^(localhost|127\.0\.0\.1)(:|$)" host))
                   (private-address? host))
          (throw (ex-info "Blocked: --host resolves to a private/internal address"
                          {:host host})))
        (if (or insecure (re-find #"^(localhost|127\.0\.0\.1)(:|$)" host))
          (str "http://" host)
          (str "https://" host)))
      (str "http://127.0.0.1:" port)))

(defn- auth-headers
  "Build auth headers from connection info."
  [{:keys [token]}]
  (cond-> {"Content-Type" "application/json"}
    token (assoc "Authorization" (str "Bearer " token))))

(defn- parse-sse-events
  "Read an SSE stream, call on-progress for each progress event.
   Returns the result from the 'result' event."
  [input-stream on-progress]
  (let [result (atom nil)]
    (with-open [rdr (io/reader input-stream)]
      (loop [event-type nil]
        (when-let [line (.readLine rdr)]
          (cond
            (str/starts-with? line "event: ")
            (recur (subs line 7))

            (str/starts-with? line "data: ")
            (let [data (json/parse-string (subs line 6) true)]
              (case event-type
                "progress" (do (when on-progress (on-progress data)) (recur nil))
                "result"   (do (reset! result data) (recur nil))
                "error"    (do (reset! result {:ok false :error (:message data)}) (recur nil))
                "done"     nil
                (recur nil)))

            :else (recur event-type)))))
    @result))

(defn- api-post!
  "POST to the daemon API. When stream? is true, requests SSE and feeds
   on-progress with each event. Returns parsed response."
  ([conn path body] (api-post! conn path body nil))
  ([conn path body on-progress]
   (let [sse?    (some? on-progress)
         headers (cond-> (auth-headers conn)
                   sse? (assoc "Accept" "text/event-stream"))
         resp    (http/post (str (base-url conn) path)
                            {:headers headers
                             :body    (json/generate-string body)
                             :timeout 600000
                             :throw   false
                             :as      (if sse? :stream :string)})]
     (cond
       (and sse? (<= 200 (:status resp) 299))
       (let [result (parse-sse-events (:body resp) on-progress)]
         (if (and (map? result) (false? (:ok result)))
           result
           {:ok true :data result}))

       sse?
       (let [body-str (slurp (:body resp))]
         (try (json/parse-string body-str true)
              (catch Exception _
                {:ok false :error (str "HTTP " (:status resp) ": " body-str)})))

       :else
       (json/parse-string (:body resp) true)))))

(defn- api-get! [conn path]
  (let [resp (http/get (str (base-url conn) path)
                       {:headers (auth-headers conn)
                        :timeout 30000
                        :throw   false})]
    (json/parse-string (:body resp) true)))

;; --- Output ---

(defn- format-duration [ms]
  (let [s (quot ms 1000) m (quot s 60) h (quot m 60) d (quot h 24)]
    (cond
      (< s 60)  (str s "s")
      (< m 60)  (str m "m " (mod s 60) "s")
      (< h 24)  (str h "h " (mod m 60) "m")
      :else     (str d "d " (mod h 24) "h"))))

(defn- format-value [k v]
  (if (= k :uptime-ms) (format-duration v) v))

(defn- print-result [data]
  (cond
    (map? data)    (doseq [[k v] (sort-by key data)
                           :when (and (some? v) (not (and (coll? v) (empty? v))))]
                     (tui/eprintln (str "  " (name k) ": " (format-value k v))))
    (vector? data) (doseq [item data]
                     (if (map? item)
                       (do (tui/eprintln (str "  " (or (:name item) (pr-str item))))
                           (doseq [[k v] (dissoc item :name)
                                   :when (and (some? v) (not (and (coll? v) (empty? v))))]
                             (tui/eprintln (str "    " (name k) ": " v))))
                       (tui/eprintln (str "  " item))))
    :else          (tui/eprintln (str "  " data))))

(defn- print-api-result
  "Print API response. Returns exit code."
  [resp]
  (if (:ok resp)
    (do (print-result (:data resp)) 0)
    (do (tui/eprintln (str (style/red "Error: ") (:error resp))) 1)))

;; --- Backend ---

(defn- ensure-backend!
  "Returns a connection map {:port N} or {:host \"addr:port\" :token \"...\"}.
   Remote mode (--host): skips JRE/JAR/daemon, connects directly."
  [flags]
  (let [effective (merge (load-config) flags)]
    (if-let [host (:host effective)]
      {:host host :token (:token effective) :insecure (:insecure effective)}
      (let [jre-path (jre/ensure!)
            jar-path (jar/ensure!)]
        (daemon/ensure! (merge {:jre-path jre-path :jar-path jar-path}
                               (select-keys effective [:db-dir :provider :model :token])))))))

(defn- url-encode [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

;; --- Path resolution ---

(defn- canonicalize-path
  "Resolve a path to absolute/canonical if it exists on disk.
   Returns the original string unchanged if it's a database name."
  [path]
  (let [f (java.io.File. path)]
    (if (.exists f) (.getCanonicalPath f) path)))

(defn- path->db-name
  "Derive a database name from a path or name. If it exists on disk,
   take the last path component (matching server-side derive-db-name).
   Otherwise return as-is (already a database name)."
  [repo]
  (let [f (java.io.File. repo)]
    (if (.exists f)
      (-> (.getCanonicalPath f) (str/replace #"/+$" "") (str/split #"/") last)
      repo)))

;; --- Command handlers (return exit codes, never call System/exit) ---

(def ^:private positional-maps
  "How positional args map to API body keys, keyed by :positional-map."
  {:ask   (fn [pos] {:repo_path (canonicalize-path (first pos))
                     :question (str/join " " (rest pos))})
   :query (fn [pos] {:query_name (first pos)
                     :repo_path (canonicalize-path (second pos))})})

(defn- build-api-body
  "Build the API request body from flags and positional args."
  [flags positional cmd-def]
  (let [mapper (get positional-maps (:positional-map cmd-def)
                    (fn [pos] (when-let [repo (first pos)]
                                {:repo_path (canonicalize-path repo)})))]
    (merge flags (mapper positional))))

(def ^:private progress-commands
  "Commands that benefit from SSE progress streaming."
  #{"import" "analyze" "enrich" "digest" "bench" "introspect"})

(defn- make-progress-handler
  "Create an SSE on-progress callback that drives a TUI progress bar.
   Uses a spinner for indeterminate (total=0) operations.
   Starts a waiting spinner immediately so the user sees feedback.
   Returns the callback fn (or nil if non-interactive)."
  [command]
  (when (and (tui/interactive?) (progress-commands command))
    (let [bar-atom     (atom nil)
          spinner-atom (atom {:spinner (spinner/start (str command "..."))
                              :message (str command "...")})]
      (fn [{:keys [current total message]}]
        (cond
          ;; Indeterminate: use spinner, stop previous spinner on message change
          (zero? total)
          (let [prev @spinner-atom]
            (when (and prev (not= (:message prev) message))
              ((:stop (:spinner prev)) (:message prev)))
            (when (or (nil? prev) (not= (:message prev) message))
              (let [s (spinner/start message)]
                (reset! spinner-atom {:spinner s :message message}))))

          ;; Start determinate bar
          (nil? @bar-atom)
          (do (when-let [prev @spinner-atom]
                ((:stop (:spinner prev)) (:message prev))
                (reset! spinner-atom nil))
              (let [b (progress/bar command total)]
                (reset! bar-atom b)
                ((:update b) current)))

          ;; Update / finish determinate bar
          :else
          (let [b @bar-atom]
            (if (= current total)
              ((:done b))
              ((:update b) current))))))))

(defn- do-api-command [{:keys [command flags positional]}]
  (let [{:keys [api-path api-method min-args usage] :as cmd-def} (cli/commands command)]
    (cond
      (not api-path)
      (do (tui/eprintln (str "Command '" command "' is not yet implemented.")) 1)

      (and min-args (< (count positional) min-args))
      (do (tui/eprintln (str "Usage: " usage))
          (when (and (zero? (count positional)) (>= (or min-args 0) 1))
            (tui/eprintln "Use `noum databases` to see imported repositories."))
          1)

      :else
      (let [conn        (ensure-backend! flags)
            body        (build-api-body flags positional cmd-def)
            progress-fn (make-progress-handler command)]
        (print-api-result (case api-method
                            :post (api-post! conn api-path body progress-fn)
                            :get  (api-get! conn api-path)))))))

(defn- do-db-get
  "GET endpoint addressed by database name. Accepts a path or name."
  [{:keys [flags positional]} path-prefix cmd-name]
  (if-let [repo (first positional)]
    (let [conn    (ensure-backend! flags)
          db-name (path->db-name repo)]
      (api-get! conn (str path-prefix (url-encode db-name))))
    {:ok false :error (str "Usage: noum " cmd-name " <repo>. Use `noum databases` to see names.")}))

(defn- do-status [parsed]
  (print-api-result (do-db-get parsed "/api/status/" "status")))

(defn- do-schema [parsed]
  (let [resp (do-db-get parsed "/api/schema/" "schema")]
    (if (:ok resp)
      (do (println (get-in resp [:data :schema])) 0)
      (do (tui/eprintln (str (style/red "Error: ") (:error resp))) 1))))

(defn- do-setup [{:keys [positional]}]
  (case (first positional)
    "desktop" (do (setup/setup-desktop!) 0)
    "code"    (do (setup/setup-code!) 0)
    (do (tui/eprintln "Usage: noum setup <desktop|code>") 1)))

(defn- do-start [{:keys [flags]}]
  (let [was-running (daemon/running?)]
    (ensure-backend! flags)
    (when was-running
      (tui/eprintln (str (style/green "✓") " Daemon already running on port " (:port (daemon/connection)))))
    0))

(defn- do-stop [_]
  (if (daemon/running?)
    (do (daemon/stop!) 0)
    (do (tui/eprintln "Daemon not running.") 0)))

(defn- do-ping [_]
  (if-let [conn (daemon/connection)]
    (let [resp (api-get! conn "/health")]
      (tui/eprintln (str (style/green "✓") " Daemon running on port " (:port conn)))
      (print-result (:data resp))
      0)
    (do (tui/eprintln (str (style/red "✗") " Daemon not running. Run 'noum start' to start it.")) 1)))

(defn- do-upgrade [_]
  (if (jar/download!)
    (do (tui/eprintln (str (style/green "✓") " Noumenon updated."))
        (tui/eprintln "To update the noum launcher itself, re-run the installer."))
    (tui/eprintln "To update the noum launcher itself, re-run the installer."))
  0)

(defn- do-serve [{:keys [flags]}]
  (let [jre-path (jre/ensure!)
        jar-path (jar/ensure!)
        java-bin (str (fs/path jre-path "bin" "java"))
        args     (cond-> [java-bin "-jar" jar-path "serve"]
                   (:db-dir flags)   (into ["--db-dir" (:db-dir flags)])
                   (:provider flags) (into ["--provider" (:provider flags)])
                   (:model flags)    (into ["--model" (:model flags)]))
        env      (cond-> (into {} (System/getenv))
                   (:token flags) (assoc "NOUMENON_TOKEN" (:token flags)))]
    (:exit @(proc/process {:cmd args :extra-env env :inherit true}))))

(defn- do-watch [{:keys [flags positional]}]
  (if (empty? positional)
    (do (tui/eprintln "Usage: noum watch <repo> [--interval N] [--analyze]") 1)
    (let [conn       (ensure-backend! flags)
          repo-path  (first positional)
          interval-s (or (some-> (:interval flags) parse-long) 30)
          body       (cond-> {:repo_path (canonicalize-path repo-path)}
                       (:analyze flags) (assoc :analyze true))]
      (tui/eprintln (str "Watching " repo-path " (polling every " interval-s "s). Ctrl+C to stop."))
      (loop [failures 0]
        (let [resp (try (api-post! conn "/api/update" body)
                        (catch Exception e {:ok false :error (.getMessage e)}))]
          (if (:ok resp)
            (do (when (not= :up-to-date (get-in resp [:data :status]))
                  (tui/eprintln (str "  Updated: " (get-in resp [:data :added] 0) " added, "
                                     (get-in resp [:data :modified] 0) " modified")))
                (Thread/sleep (* interval-s 1000))
                (recur 0))
            (let [n (inc failures)]
              (tui/eprintln (str (style/yellow "  Warning: ") "update failed: " (:error resp)))
              (if (>= n 3)
                (do (tui/eprintln (str (style/red "Error: ") "3 consecutive failures, stopping watch.")) 1)
                (do (Thread/sleep (* interval-s 1000))
                    (recur n))))))))))

(defn- do-delete [{:keys [flags positional]}]
  (if (empty? positional)
    (do (tui/eprintln "Usage: noum delete <name> [--force]")
        (tui/eprintln "Use `noum databases` to see available database names.")
        1)
    (let [db-name (path->db-name (first positional))
          conn    (ensure-backend! flags)]
      (if (and (not (:force flags))
               (not (confirm/ask (str "Delete database '" db-name "'? This cannot be undone.") false)))
        (do (tui/eprintln "Aborted.") 0)
        (let [resp (try
                     (let [r (http/delete (str (base-url conn) "/api/databases/" (url-encode db-name))
                                          {:headers (auth-headers conn)
                                           :timeout 30000
                                           :throw   false})]
                       (json/parse-string (:body r) true))
                     (catch Exception e {:ok false :error (.getMessage e)}))]
          (print-api-result resp))))))

(defn- do-results [{:keys [flags positional]}]
  (if (empty? positional)
    (do (tui/eprintln "Usage: noum results <repo> [--run-id <id>]")
        (tui/eprintln "Omit --run-id to get the latest run.")
        1)
    (let [conn   (ensure-backend! flags)
          repo   (canonicalize-path (first positional))
          params (str "?repo_path=" (url-encode repo)
                      (when-let [rid (:run-id flags)] (str "&run_id=" (url-encode rid))))]
      (print-api-result (api-get! conn (str "/api/benchmark/results" params))))))

(defn- do-compare [{:keys [flags positional]}]
  (if (< (count positional) 3)
    (do (tui/eprintln "Usage: noum compare <repo> <run-a> <run-b>")
        (tui/eprintln "Use `noum results <repo>` to find run IDs.")
        1)
    (let [conn   (ensure-backend! flags)
          repo   (canonicalize-path (first positional))
          params (str "?repo_path=" (url-encode repo)
                      "&run_id_a=" (url-encode (second positional))
                      "&run_id_b=" (url-encode (nth positional 2)))]
      (print-api-result (api-get! conn (str "/api/benchmark/compare" params))))))

(defn- do-history [{:keys [flags positional]}]
  (let [atype (or (:type flags) (first positional))
        aname (or (:name flags) (second positional))]
    (cond
      (nil? atype)
      (do (tui/eprintln "Usage: noum history rules")
          (tui/eprintln "       noum history prompt <name>")
          1)

      (not (#{"prompt" "rules"} atype))
      (do (tui/eprintln (str "Unknown type: " atype ". Must be 'prompt' or 'rules'.")) 1)

      (and (= "prompt" atype) (nil? aname))
      (do (tui/eprintln "Usage: noum history prompt <name>")
          (tui/eprintln "Hint: prompt names include 'analyze-file', 'agent-system', 'introspect'.")
          1)

      :else
      (let [conn   (ensure-backend! flags)
            params (str "?type=" atype (when aname (str "&name=" aname)))
            resp   (api-get! conn (str "/api/artifacts/history" params))]
        (print-api-result resp)))))

(defn- do-help [{:keys [positional]}]
  (if-let [cmd (first positional)]
    (if-let [help (cli/format-command-help cmd)]
      (tui/eprintln help)
      (tui/eprintln (str "Unknown command: " cmd)))
    (tui/eprintln (cli/format-help)))
  0)

(defn- do-version [_]
  (tui/eprintln (str "noum " version))
  (when (jar/installed?)
    (try
      (when-let [conn (daemon/connection)]
        (let [resp (api-get! conn "/health")]
          (when (:ok resp)
            (tui/eprintln (str "noumenon " (get-in resp [:data :version]))))))
      (catch Exception _
        (tui/eprintln "  (daemon not reachable)"))))
  0)

(defn- do-open [{:keys [flags]}]
  (let [conn (ensure-backend! flags)
        port (:port conn)]
    (tui/eprintln (str "Opening Noumenon UI (daemon on port " port ")..."))
    (let [result @(proc/process
                   {:cmd ["npx" "electron" "ui/"]
                    :dir (or (System/getenv "NOUMENON_ROOT") ".")
                    :env (assoc (into {} (System/getenv))
                                "NOUMENON_PORT" (str port))
                    :inherit true})]
      (:exit result))))

;; --- Dispatch ---

(defn- do-demo [{:keys [flags]}]
  (try
    (demo/install! flags)
    (let [port (ensure-backend! {})]
      (tui/eprintln "")
      (tui/eprintln (str (style/green "Done!") " Demo database ready."))
      (tui/eprintln "")
      (tui/eprintln "Try these commands:")
      (tui/eprintln (str "  noum ask noumenon " (style/green "\"Describe the architecture\"")))
      (tui/eprintln (str "  noum ask noumenon " (style/green "\"What are the main components?\"")))
      (tui/eprintln "  noum query components noumenon")
      (tui/eprintln "  noum status noumenon")
      (tui/eprintln "")
      (tui/eprintln "Run `noum databases` to see all databases.")
      0)
    (catch Exception e
      (if (:cancelled (ex-data e))
        0
        (do (tui/eprintln (str "Error: " (.getMessage e)))
            1)))))

(def ^:private dispatch
  {"help"       do-help
   "version"    do-version
   "demo"       do-demo
   "setup"      do-setup
   "start"      do-start
   "stop"       do-stop
   "ping"       do-ping
   "upgrade"    do-upgrade
   "serve"      do-serve
   "watch"      do-watch
   "status"     do-status
   "schema"     do-schema
   "delete"     do-delete
   "results"    do-results
   "compare"    do-compare
   "history"    do-history
   "open"       do-open})

(defn -main [& args]
  (let [parsed (cli/parse-args args)]
    (if (:error parsed)
      (do (case (:error parsed)
            :no-args         (tui/eprintln (cli/format-help))
            :unknown-command (tui/eprintln (str "Unknown command: " (:command parsed)
                                                "\n\n" (cli/format-help))))
          (System/exit 1))
      (let [handler (get dispatch (:command parsed) do-api-command)]
        (System/exit (handler parsed))))))
