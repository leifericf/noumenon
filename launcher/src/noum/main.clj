(ns noum.main
  "Entry point for the noum launcher."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noum.api :as api]
            [noum.cli :as cli]
            [noum.interactive :as interactive]
            [noum.daemon :as daemon]
            [noum.demo :as demo]
            [noum.electron :as electron]
            [noum.jar :as jar]
            [noum.jre :as jre]
            [noum.paths :as paths]
            [noum.setup :as setup]
            [noum.tui.confirm :as confirm]
            [noum.tui.core :as tui]
            [noum.tui.progress :as progress]
            [noum.tui.spinner :as spinner]
            [noum.tui.style :as style]))

(def ^:private version paths/version)

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

(defn- parse-param-flag
  "Convert --param 'key=value' string into a map entry, or nil."
  [s]
  (when (string? s)
    (let [idx (str/index-of s "=")]
      (when (and idx (pos? idx))
        {(subs s 0 idx) (subs s (inc idx))}))))

(defn- underscore-keys
  "Convert hyphenated keyword keys to underscored (CLI convention → API convention)."
  [m]
  (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "-" "_")) v])) m))

(defn- build-api-body
  "Build the API request body from flags and positional args.
   Converts --param key=value into nested {:params {key value}}.
   Converts hyphenated flag keys to underscored for the HTTP API."
  [flags positional cmd-def]
  (let [mapper (get positional-maps (:positional-map cmd-def)
                    (fn [pos] (when-let [repo (first pos)]
                                {:repo_path (canonicalize-path repo)})))
        param-map (some-> (:param flags) parse-param-flag)]
    (cond-> (merge (underscore-keys (dissoc flags :param)) (mapper positional))
      param-map (assoc :params param-map))))

(def ^:private progress-commands
  "Commands that benefit from SSE progress streaming."
  #{"import" "analyze" "enrich" "digest" "bench" "introspect"})

(defn- make-progress-handler
  "Create an SSE on-progress callback that drives a TUI progress bar.
   Uses a spinner for indeterminate (total=0) operations.
   Starts a waiting spinner immediately so the user sees feedback.
   Returns {:handler callback-fn :cleanup cleanup-fn} or nil if non-interactive."
  [command]
  (when (and (tui/interactive?) (progress-commands command))
    (let [bar-atom     (atom nil)
          spinner-atom (atom {:spinner (spinner/start (str command "..."))
                              :message (str command "...")})
          cleanup!     (fn []
                         (when-let [prev @spinner-atom]
                           ((:fail (:spinner prev)) (:message prev))
                           (reset! spinner-atom nil))
                         (when-let [b @bar-atom]
                           ((:done b))
                           (reset! bar-atom nil)))
          handler      (fn [evt]
                         (let [total   (or (:total evt) 0)
                               current (or (:current evt) 0)
                               message (:message evt)]
                           (cond
                             ;; Indeterminate: use spinner
                             (zero? total)
                             (do (when-let [b @bar-atom]
                                   ((:done b))
                                   (reset! bar-atom nil))
                                 (let [prev @spinner-atom]
                                   (when (and prev (not= (:message prev) message))
                                     ((:stop (:spinner prev)) (:message prev)))
                                   (when (or (nil? prev) (not= (:message prev) message))
                                     (let [s (spinner/start message)]
                                       (reset! spinner-atom {:spinner s :message message})))))

                             ;; Start determinate bar (or restart when total changes)
                             (or (nil? @bar-atom)
                                 (not= (:total (meta @bar-atom)) total))
                             (do (when-let [b @bar-atom]
                                   ((:done b)))
                                 (when-let [prev @spinner-atom]
                                   ((:stop (:spinner prev)) (:message prev))
                                   (reset! spinner-atom nil))
                                 (let [label (or (:step evt) message command)
                                       b     (progress/bar label total)]
                                   (reset! bar-atom (with-meta b {:total total}))
                                   ((:update b) current)))

                             ;; Update / finish determinate bar
                             :else
                             (let [b @bar-atom]
                               (if (= current total)
                                 (do ((:done b))
                                     (reset! bar-atom nil))
                                 ((:update b) current))))))]
      {:handler handler :cleanup cleanup!})))

(defn do-api-command [{:keys [command flags positional]}]
  (let [{:keys [api-path api-method min-args usage] :as cmd-def} (cli/commands command)]
    (cond
      (not api-path)
      (do (tui/eprintln (str "Internal error: no handler for command '" command "'. Please report this bug.")) 1)

      (and min-args (< (count positional) min-args))
      (do (tui/eprintln (str "Usage: " usage))
          (when (and (zero? (count positional)) (>= (or min-args 0) 1))
            (tui/eprintln "Use `noum databases` to see imported repositories."))
          1)

      :else
      (let [conn     (api/ensure-backend! flags)
            body     (build-api-body flags positional cmd-def)
            progress (make-progress-handler command)]
        (try
          (print-api-result (case api-method
                              :post (api/post! conn api-path body (:handler progress))
                              :get  (api/get! conn api-path)))
          (finally
            (when-let [cleanup (:cleanup progress)]
              (cleanup))))))))

(defn- do-db-get
  "GET endpoint addressed by database name. Accepts a path or name."
  [{:keys [flags positional]} path-prefix cmd-name]
  (if-let [repo (first positional)]
    (let [conn    (api/ensure-backend! flags)
          db-name (path->db-name repo)]
      (api/get! conn (str path-prefix (api/url-encode db-name))))
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
    "desktop" (do (setup/setup-desktop!)
                  (tui/eprintln (str (style/green "Setup complete.") " Run `noum status` to verify."))
                  0)
    "code"    (do (setup/setup-code!)
                  (tui/eprintln (str (style/green "Setup complete.") " Run `noum status` to verify."))
                  0)
    (do (tui/eprintln "Usage: noum setup <desktop|code>") 1)))

(defn- do-start [{:keys [flags]}]
  (let [was-running (daemon/running?)]
    (api/ensure-backend! flags)
    (when was-running
      (tui/eprintln (str (style/green "✓") " Daemon already running on port " (:port (daemon/connection)))))
    0))

(defn- do-stop [_]
  (if (daemon/running?)
    (do (daemon/stop!) 0)
    (do (tui/eprintln "Daemon not running.") 0)))

(defn- do-ping [_]
  (if-let [conn (daemon/connection)]
    (let [resp (api/get! conn "/health")]
      (tui/eprintln (str (style/green "✓") " Daemon running on port " (:port conn)))
      (print-result (:data resp))
      0)
    (do (tui/eprintln (str (style/red "✗") " Daemon not running. Run 'noum start' to start it.")) 1)))

(defn- do-upgrade [_]
  (let [s (spinner/start "Downloading latest version...")]
    (try
      (if (jar/download!)
        (do (when (daemon/running?) (daemon/stop!))
            ((:stop s) "Noumenon updated.")
            (tui/eprintln "To update the noum launcher itself, re-run the installer.")
            0)
        (do ((:stop s) "Already at latest version.")
            (tui/eprintln "To update the noum launcher itself, re-run the installer.")
            0))
      (catch Exception e
        ((:fail s) (str "upgrade failed: " (.getMessage e)))
        1))))

(defn- do-serve [{:keys [flags]}]
  (let [jre-path (jre/ensure!)
        jar-path (jar/ensure! version)
        java-bin (str (fs/path jre-path "bin" "java"))
        args     (cond-> [java-bin "-jar" jar-path "serve"]
                   (:db-dir flags)   (into ["--db-dir" (:db-dir flags)])
                   (:provider flags) (into ["--provider" (:provider flags)])
                   (:model flags)    (into ["--model" (:model flags)]))
        env      (cond-> (into {} (System/getenv))
                   (:token flags) (assoc "NOUMENON_TOKEN" (:token flags)))]
    (:exit @(proc/process {:cmd args :extra-env env :inherit true}))))

(defn- do-watch [{:keys [flags positional]}]
  (if-not (seq positional)
    (do (tui/eprintln "Usage: noum watch <repo> [--interval N] [--analyze]") 1)
    (let [conn       (api/ensure-backend! flags)
          repo-path  (first positional)
          interval-s (or (some-> (:interval flags) parse-long) 30)
          body       (cond-> {:repo_path (canonicalize-path repo-path)}
                       (:analyze flags) (assoc :analyze true))]
      (tui/eprintln (str "Watching " repo-path " (polling every " interval-s "s). Ctrl+C to stop."))
      (loop [failures 0]
        (let [resp (try (api/post! conn "/api/update" body)
                        (catch Exception e
                          {:ok false :error (or (.getMessage e) (str (class e)))}))]
          (if (:ok resp)
            (do (when (not= :up-to-date (get-in resp [:data :status]))
                  (tui/eprintln (str "  Updated: " (get-in resp [:data :added] 0) " added, "
                                     (get-in resp [:data :modified] 0) " modified")))
                (Thread/sleep (* interval-s 1000))
                (recur 0))
            (let [n       (inc failures)
                  err-msg (str/replace (or (:error resp) "unknown error")
                                       #"(?:java\.\w+\.)*(\w+Exception): " "$1: ")]
              (tui/eprintln (str (style/yellow "  Warning: ") repo-path ": update failed (" n "/3): " err-msg))
              (if (>= n 3)
                (do (tui/eprintln (str (style/red "Error: ") repo-path ": 3 consecutive failures, stopping watch."))
                    (tui/eprintln (str "  Last error: " err-msg))
                    (tui/eprintln "  Try `noum status` to check daemon health, or restart with `noum stop && noum start`.")
                    1)
                (do (Thread/sleep (* interval-s 1000))
                    (recur n))))))))))

(defn- do-delete [{:keys [flags positional]}]
  (if-not (seq positional)
    (do (tui/eprintln "Usage: noum delete <name> [--force]")
        (tui/eprintln "Use `noum databases` to see available database names.")
        1)
    (let [db-name (path->db-name (first positional))
          conn    (api/ensure-backend! flags)]
      (if (and (not (:force flags))
               (not (confirm/ask (str "Delete database '" db-name "'? This cannot be undone.") false)))
        (do (tui/eprintln "Aborted.") 0)
        (let [resp (try
                     (let [r (http/delete (str (api/base-url conn) "/api/databases/" (api/url-encode db-name))
                                          {:headers (api/auth-headers conn)
                                           :timeout 30000
                                           :throw   false})]
                       (json/parse-string (:body r) true))
                     (catch Exception e {:ok false :error (.getMessage e)}))]
          (print-api-result resp))))))

(defn- do-results [{:keys [flags positional]}]
  (if-not (seq positional)
    (do (tui/eprintln "Usage: noum results <repo> [--run-id <id>]")
        (tui/eprintln "Omit --run-id to get the latest run.")
        1)
    (let [conn   (api/ensure-backend! flags)
          repo   (canonicalize-path (first positional))
          params (str "?repo_path=" (api/url-encode repo)
                      (when-let [rid (:run-id flags)] (str "&run_id=" (api/url-encode rid))))]
      (print-api-result (api/get! conn (str "/api/benchmark/results" params))))))

(defn- do-compare [{:keys [flags positional]}]
  (if (< (count positional) 3)
    (do (tui/eprintln "Usage: noum compare <repo> <run-a> <run-b>")
        (tui/eprintln "Use `noum results <repo>` to find run IDs.")
        1)
    (let [conn   (api/ensure-backend! flags)
          repo   (canonicalize-path (first positional))
          params (str "?repo_path=" (api/url-encode repo)
                      "&run_id_a=" (api/url-encode (second positional))
                      "&run_id_b=" (api/url-encode (nth positional 2)))]
      (print-api-result (api/get! conn (str "/api/benchmark/compare" params))))))

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
      (let [names (->> (io/resource "prompts/")
                       io/file .listFiles seq
                       (keep #(when (str/ends-with? (.getName %) ".edn")
                                (str/replace (.getName %) ".edn" "")))
                       sort)]
        (tui/eprintln "Usage: noum history prompt <name>")
        (tui/eprintln (str "Available prompts: " (str/join ", " (map #(str "'" % "'") names))))
        1)

      :else
      (let [conn   (api/ensure-backend! flags)
            params (str "?type=" atype (when aname (str "&name=" aname)))
            resp   (api/get! conn (str "/api/artifacts/history" params))]
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
  (if-not (jar/installed?)
    (tui/eprintln "Daemon: not installed")
    (try
      (if-let [conn (daemon/connection)]
        (let [resp (api/get! conn "/health")]
          (if (:ok resp)
            (tui/eprintln (str "noumenon " (get-in resp [:data :version])))
            (tui/eprintln "Daemon: not responding")))
        (tui/eprintln "Daemon: not running"))
      (catch Exception _
        (tui/eprintln "Daemon: not reachable"))))
  0)

(defn- do-open [{:keys [flags]}]
  (let [conn (api/ensure-backend! flags)
        port (:port conn)
        dev? (System/getenv "NOUMENON_ROOT")]
    (tui/eprintln (str "Opening Noumenon UI (daemon on port " port ")..."))
    (let [exit-code
          (try
            (if dev?
              ;; Dev mode: source checkout with npx
              (:exit @(proc/process
                       {:cmd ["npx" "electron" "ui/"]
                        :dir dev?
                        :env (assoc (into {} (System/getenv))
                                    "NOUMENON_PORT" (str port))
                        :inherit true}))
              ;; Installed: download packaged app and launch
              (let [app-path (electron/ensure!)]
                (:exit @(proc/process
                         {:cmd (electron/launch-cmd app-path port)
                          :env (electron/launch-env port)
                          :inherit true}))))
            (catch Exception e
              (tui/eprintln (str (style/red "Error: ") (.getMessage e)))
              127))]
      (when-not (zero? (or exit-code 0))
        (tui/eprintln (str (style/red "Error: ") "Electron UI exited with code " exit-code "."))
        (when dev?
          (tui/eprintln "  Ensure Node.js is installed (https://nodejs.org) and Electron is available:")
          (tui/eprintln "    node --version && npx electron --version")
          (tui/eprintln "  Then run:")
          (tui/eprintln "    cd ui && npm install")))
      (or exit-code 0))))

;; --- Query (raw + as-of) ---

(defn- do-query [{:keys [flags positional] :as parsed}]
  (cond
    (and (:raw flags) (:as-of flags))
    (do (tui/eprintln "Error: --raw and --as-of cannot be used together.") 1)

    (:raw flags)
    (if-not (string? (:raw flags))
      (do (tui/eprintln "Usage: noum query --raw '<datalog>' <repo> [--limit N]") 1)
      (let [conn (api/ensure-backend! flags)
            body (cond-> {:query     (:raw flags)
                          :repo_path (canonicalize-path (or (first positional) "."))}
                   (:limit flags) (assoc :limit (parse-long (:limit flags))))]
        (print-api-result (api/post! conn "/api/query-raw" body))))

    (:as-of flags)
    (if (< (count positional) 2)
      (do (tui/eprintln "Usage: noum query <name> <repo> --as-of <date> [--param key=value]") 1)
      (let [conn (api/ensure-backend! flags)
            body (cond-> {:query_name (first positional)
                          :repo_path  (canonicalize-path (second positional))
                          :as_of      (:as-of flags)}
                   (:limit flags) (assoc :limit (parse-long (:limit flags)))
                   (:param flags) (assoc :params (parse-param-flag (:param flags))))]
        (print-api-result (api/post! conn "/api/query-as-of" body))))

    :else
    (do-api-command parsed)))

;; --- Introspect (status / stop / history) ---

(defn- do-introspect [{:keys [flags] :as parsed}]
  (cond
    (string? (:status flags))
    (let [conn (api/ensure-backend! flags)]
      (print-api-result (api/get! conn (str "/api/introspect/status?run_id=" (api/url-encode (:status flags))))))

    (string? (:stop flags))
    (let [conn (api/ensure-backend! flags)]
      (print-api-result (api/post! conn "/api/introspect/stop" {:run_id (:stop flags)})))

    (:history flags)
    (let [conn       (api/ensure-backend! flags)
          query-name (if (string? (:history flags)) (:history flags) "introspect-runs")]
      (print-api-result (api/get! conn (str "/api/introspect/history?query_name=" (api/url-encode query-name)))))

    :else
    (do-api-command parsed)))

;; --- Sessions / Feedback / Settings ---

(defn- do-sessions [{:keys [flags positional]}]
  (let [conn (api/ensure-backend! flags)]
    (if-let [session-id (first positional)]
      (print-api-result (api/get! conn (str "/api/ask/sessions/" (api/url-encode session-id))))
      (print-api-result (api/get! conn "/api/ask/sessions")))))

(defn- do-feedback [{:keys [flags positional]}]
  (let [session-id (first positional)
        rating     (second positional)]
    (if-not (#{"positive" "negative"} rating)
      (do (tui/eprintln "Usage: noum feedback <session-id> positive|negative [--comment \"...\"]")
          (tui/eprintln "Rating must be 'positive' or 'negative'.")
          1)
      (let [conn (api/ensure-backend! flags)
            body (cond-> {:feedback rating}
                   (:comment flags) (assoc :comment (:comment flags)))]
        (print-api-result (api/post! conn (str "/api/ask/sessions/" (api/url-encode session-id) "/feedback") body))))))

(defn- do-settings [{:keys [flags positional]}]
  (let [conn (api/ensure-backend! flags)]
    (case (count positional)
      0 (print-api-result (api/get! conn "/api/settings"))
      1 (let [k    (first positional)
              resp (api/get! conn (str "/api/settings/" k))]
          (if (:ok resp)
            (do (tui/eprintln (str "  " k ": " (:data resp))) 0)
            ;; Fall back to fetching all if per-key endpoint not available
            (let [all (api/get! conn "/api/settings")]
              (if (:ok all)
                (let [val (get (:data all) (keyword k))]
                  (if (some? val)
                    (do (tui/eprintln (str "  " k ": " val)) 0)
                    (do (tui/eprintln (str "No setting: " k)) 1)))
                (print-api-result all)))))
      (print-api-result (api/post! conn "/api/settings"
                                   {:key (first positional) :value (second positional)})))))

;; --- Connection management ---

(defn- do-connect [{:keys [positional flags]}]
  (if-not (seq positional)
    (do (tui/eprintln "Usage: noum connect <url-or-name> [--token <token>]")
        (tui/eprintln "       noum connect local")
        1)
    (let [target (first positional)]
      (if (= target "local")
        (do (api/set-active-connection! "local")
            (tui/eprintln (str (style/green "Switched to local mode.")
                               " Commands will use the local daemon."))
            0)
        (let [token (:token flags)
              ;; Derive a connection name from the URL
              ;; Derive name from hostname (drop TLD): noumenon.example.com -> noumenon
              name  (or (:name flags)
                        (let [host (str/replace target #"https?://" "")
                              host (first (str/split host #"[:/]"))
                              parts (str/split host #"\.")]
                          (if (> (count parts) 1)
                            (first parts)
                            host)))
              conn  {:host target :token token :insecure (:insecure flags)}]
          (when (and token (:insecure flags))
            (tui/eprintln (str (style/yellow "WARNING: ") "Sending auth token over unencrypted HTTP. "
                               "Use HTTPS unless this is a trusted local network.")))
          ;; Validate by hitting /health
          (try
            (let [resp (http/get (str (api/base-url conn) "/health")
                                 {:headers (api/auth-headers conn)
                                  :timeout 5000
                                  :throw   false})]
              (if (= 200 (:status resp))
                (do (api/add-connection! name conn)
                    (tui/eprintln (str (style/green "Connected to ") target " as '" name "'."))
                    (when token
                      (tui/eprintln (str (style/dim "  Token saved to ~/.noumenon/config.edn"))))
                    0)
                (do (tui/eprintln (str (style/red "Error: ") "Server returned HTTP " (:status resp)))
                    1)))
            (catch Exception e
              (tui/eprintln (str (style/red "Error: ") "Could not reach " target ": " (.getMessage e)))
              1)))))))

(defn- do-connections [_]
  (let [conns (api/list-connections)]
    (doseq [c conns
            [name info] c]
      (let [marker (if (:active? info) (style/green " *") "  ")]
        (tui/eprintln (str marker " " name
                           (when (:host info) (str " -> " (:host info)))
                           (when (and (:mode info) (not= (clojure.core/name (:mode info)) name))
                             (str " (" (clojure.core/name (:mode info)) ")"))))))
    0))

(defn- do-disconnect [{:keys [positional]}]
  (if-not (seq positional)
    (do (tui/eprintln "Usage: noum disconnect <name>") 1)
    (let [name   (first positional)
          conns  (api/list-connections)
          exists (some (fn [c] (contains? c name)) conns)]
      (if exists
        (do (api/remove-connection! name)
            (tui/eprintln (str "Removed connection '" name "'."))
            0)
        (do (tui/eprintln (str (style/yellow "No connection named '") name (style/yellow "'.")))
            1)))))

;; --- Dispatch ---

(defn- do-demo [{:keys [flags]}]
  (try
    (demo/install! flags)
    (let [_port (api/ensure-backend! {})]
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

(def dispatch
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
   "open"       do-open
   "query"      do-query
   "introspect" do-introspect
   "sessions"   do-sessions
   "feedback"   do-feedback
   "settings"   do-settings
   "connect"    do-connect
   "connections" do-connections
   "disconnect" do-disconnect})

(defn -main [& args]
  (let [parsed (cli/parse-args args)]
    (if (:error parsed)
      (case (:error parsed)
        :no-args         (if (tui/interactive?)
                           (System/exit (interactive/run! dispatch do-api-command))
                           (do (tui/eprintln (cli/format-help))
                               (System/exit 1)))
        :unknown-command (do (tui/eprintln (str "Unknown command: " (:command parsed)
                                                "\n\n" (cli/format-help)))
                             (System/exit 1)))
      (let [handler (get dispatch (:command parsed) do-api-command)]
        (System/exit (handler parsed))))))
