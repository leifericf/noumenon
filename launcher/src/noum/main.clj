(ns noum.main
  "Entry point for the noum launcher."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
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
  (cond
    (= k :uptime-ms) (format-duration v)
    (float? v)       (format "%.2f" (double v))
    :else            v))

(defn- print-flat-map
  "Print a flat map indented by prefix. Skips nil/empty values."
  [m prefix]
  (doseq [[k v] (sort-by key m)
          :when (and (some? v) (not (and (coll? v) (empty? v))))]
    (if (map? v)
      (do (tui/eprintln (str prefix (name k) ":"))
          (print-flat-map v (str prefix "  ")))
      (tui/eprintln (str prefix (name k) ": " (format-value k v))))))

(defn- print-result [data]
  (cond
    (map? data)    (print-flat-map data "  ")
    (vector? data) (doseq [item data]
                     (if (map? item)
                       (do (tui/eprintln (str "  " (or (:name item) (pr-str item))))
                           (print-flat-map (dissoc item :name) "    "))
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
  "Convert a `--param` 'key=value' string into a single-entry map, or nil
   for malformed input. Used internally by `params-map`."
  [s]
  (when (string? s)
    (let [idx (str/index-of s "=")]
      (when (and idx (pos? idx))
        {(subs s 0 idx) (subs s (inc idx))}))))

(defn- params-map
  "Merge a vector of `--param k=v` strings (as accumulated by
   `cli/extract-flags`) into a single {key value} map. Returns nil when
   no usable params are present."
  [vs]
  (let [merged (apply merge (keep parse-param-flag vs))]
    (when (seq merged) merged)))

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
        param-map (params-map (:param flags))]
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

(defn- ping-target
  "Pick the connection to ping for `do-ping` / `do-version` without
   spawning a local daemon. Order: explicit `--host`, then the saved
   active named connection, then the local daemon (read-only check via
   `daemon/connection`). Returns nil if local mode and no daemon is up."
  [flags]
  (let [active (api/active-connection)]
    (cond
      (:host flags) {:host     (:host flags)
                     :token    (:token flags)
                     :insecure (:insecure flags)}
      (:host active) active
      :else          (daemon/connection))))

(defn- do-ping [{:keys [flags]}]
  (if-let [conn (ping-target flags)]
    (let [resp (api/get! conn "/health")]
      (if (:ok resp)
        (do (tui/eprintln (str (style/green "✓") " Daemon healthy"
                               (cond
                                 (:host conn) (str " at " (:host conn))
                                 (:port conn) (str " on port " (:port conn)))))
            (print-result (:data resp))
            0)
        (do (tui/eprintln (str (style/red "✗ ") (:error resp))) 1)))
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

(defn- parse-watch-interval
  "Interpret the --interval flag. Returns a positive integer (defaulting
   to 30 when absent) or `{:error <message>}` on garbage / non-positive
   input. Pulled out of `do-watch` so validation can run before any
   `ensure-backend!` / HTTP call and so the gate is unit-testable."
  [raw]
  (cond
    (nil? raw)             30
    (let [n (parse-long (str raw))] (and n (pos? n))) (parse-long (str raw))
    :else                  {:error (str "--interval must be a positive integer (got " raw ")")}))

(defn- watch-loop!
  "The actual polling loop. Extracted so do-watch can validate and fail
   fast before reaching it."
  [{:keys [conn repo-path body interval-s]}]
  (loop [failures 0]
    (let [resp (try (api/post! conn "/api/update" body)
                    (catch Exception e
                      {:ok false :error (or (.getMessage e) (str (class e)))}))]
      (if (:ok resp)
        (do (when (not= :up-to-date (get-in resp [:data :status]))
              (tui/eprintln (str "  Updated: " (get-in resp [:data :added] 0) " added, "
                                 (get-in resp [:data :modified] 0) " modified, "
                                 (get-in resp [:data :deleted] 0) " deleted")))
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
                (recur n))))))))

(defn- do-watch [{:keys [flags positional]}]
  (if-not (seq positional)
    (do (tui/eprintln "Usage: noum watch <repo> [--interval N] [--analyze]") 1)
    (let [interval-or-err (parse-watch-interval (:interval flags))]
      (if (map? interval-or-err)
        (do (tui/eprintln (str (style/red "Error: ") (:error interval-or-err))) 1)
        (let [conn      (api/ensure-backend! flags)
              repo-path (first positional)
              body      (cond-> {:repo_path (canonicalize-path repo-path)}
                          (:analyze flags) (assoc :analyze true))]
          (tui/eprintln (str "Watching " repo-path " (polling every " interval-or-err "s). Ctrl+C to stop."))
          (watch-loop! {:conn conn :repo-path repo-path :body body :interval-s interval-or-err}))))))

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
                       (api/parse-body (:body r)))
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
      ;; Don't try to enumerate prompt files from the launcher classpath:
      ;; the daemon's `resources/prompts/` directory isn't bundled with
      ;; the bb-side launcher (the two are separate projects), so
      ;; `(io/resource "prompts/")` returned nil and `io/file` NPE'd.
      ;; Until there's a daemon endpoint to list available names, ask
      ;; the user to provide one.
      (do (tui/eprintln "Usage: noum history prompt <name>")
          (tui/eprintln "Common names: 'agent-system', 'agent-examples', 'analyze-file', 'introspect', 'synthesize-merge'.")
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

(defn- do-version [{:keys [flags]}]
  (tui/eprintln (str "noum " version))
  (if-not (jar/installed?)
    (tui/eprintln "Daemon: not installed")
    (try
      (if-let [conn (ping-target (or flags {}))]
        (let [resp (api/get! conn "/health")]
          (if (:ok resp)
            (tui/eprintln (str "noumenon " (get-in resp [:data :version])))
            (tui/eprintln "Daemon: not responding")))
        (tui/eprintln "Daemon: not running"))
      (catch Exception _
        (tui/eprintln "Daemon: not reachable"))))
  0)

(defn- app-dev-root
  "Locate a noumenon-app source checkout for dev-mode launch.
   Resolution order: $NOUMENON_APP_ROOT, then ../noumenon-app sibling
   of $NOUMENON_ROOT, else nil (caller falls back to installed app)."
  []
  (let [explicit (System/getenv "NOUMENON_APP_ROOT")
        sibling  (some-> (System/getenv "NOUMENON_ROOT")
                         (io/file ".." "noumenon-app")
                         .getCanonicalFile
                         str)]
    (some #(when (and % (fs/exists? %)) %) [explicit sibling])))

(defn- do-open [{:keys [flags]}]
  (let [conn     (api/ensure-backend! flags)
        port     (:port conn)
        dev-root (app-dev-root)]
    (tui/eprintln (str "Opening Noumenon UI (daemon on port " port ")..."))
    (let [exit-code
          (try
            (if dev-root
              ;; Dev mode: noumenon-app source checkout with npx
              (:exit @(proc/process
                       {:cmd ["npx" "electron" "."]
                        :dir dev-root
                        :env (assoc (into {} (System/getenv))
                                    "NOUMENON_PORT" (str port))
                        :inherit true}))
              ;; Installed: download packaged app from leifericf/noumenon-app and launch
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
        (when dev-root
          (tui/eprintln (str "  Dev-mode launched from " dev-root "."))
          (tui/eprintln "  Ensure Node.js is installed (https://nodejs.org) and dependencies are installed:")
          (tui/eprintln (str "    cd " dev-root " && npm install"))))
      (or exit-code 0))))

;; --- Query (raw + as-of) ---

(defn- maybe-auto-federate
  "When the active connection is hosted and the local repo's HEAD has
   diverged from the trunk DB's :repo/head-sha, rewrite parsed flags to
   set :federate, :basis-sha, and :branch — turning a plain `noum query`
   into the federated path. A one-line yellow banner makes the rerouting
   visible. No-op when --federate is already explicit, --no-auto-federate
   was passed, the launcher setting :federation/auto-route is false, or
   the active connection is local."
  [{:keys [flags positional] :as parsed}]
  (let [opt-out? (or (:federate flags)
                     (:no-auto-federate flags)
                     (:raw flags)
                     (:as-of flags)
                     (< (count positional) 2)
                     (not (api/launcher-setting :federation/auto-route true)))]
    (if opt-out?
      parsed
      (let [hosted (api/active-connection)
            cwd    (canonicalize-path (second positional))
            ctx    (when hosted (api/detect-federation-context cwd hosted))]
        (if-not ctx
          parsed
          (do
            (tui/eprintln (style/yellow
                           (str "Federating against local delta @"
                                (subs (:basis-sha ctx) 0 7) "…")))
            (update parsed :flags merge {:federate  true
                                         :basis-sha (:basis-sha ctx)
                                         :branch    (:branch ctx)})))))))

(defn- do-query [parsed]
  (let [{:keys [flags positional] :as parsed} (maybe-auto-federate parsed)]
    (cond
      (and (:raw flags) (:as-of flags))
      (do (tui/eprintln "Error: --raw and --as-of cannot be used together.") 1)

      (and (:federate flags) (or (:raw flags) (:as-of flags)))
      (do (tui/eprintln "Error: --federate cannot be combined with --raw or --as-of.") 1)

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
                     (:param flags) (assoc :params (params-map (:param flags))))]
          (print-api-result (api/post! conn "/api/query-as-of" body))))

      (:federate flags)
      (cond
        (< (count positional) 2)
        (do (tui/eprintln "Usage: noum query <name> <repo> --federate --basis-sha <sha>") 1)
        (not (:basis-sha flags))
        (do (tui/eprintln "Error: --federate requires --basis-sha <sha>") 1)
        :else
        (let [conn (api/ensure-backend! flags)
              body (cond-> {:query_name (first positional)
                            :repo_path  (canonicalize-path (second positional))
                            :basis_sha  (:basis-sha flags)}
                     (:branch flags) (assoc :branch (:branch flags))
                     (:limit flags)  (assoc :limit (parse-long (:limit flags)))
                     (:param flags)  (assoc :params (params-map (:param flags))))
              resp (api/post! conn "/api/query-federated" body)]
          (when (and (:ok resp) (false? (get-in resp [:data :federation-safe?])))
            (tui/eprintln (style/yellow
                           "Warning: query is not federation-safe — returning trunk-only results.")))
          (print-api-result resp)))

      :else
      (do-api-command parsed))))

(defn- do-ask
  "noum ask <repo> <question>. When the active connection is hosted and
   local HEAD diverges from trunk's :repo/head-sha, emit a yellow banner
   warning the user — there is no federated ask endpoint in v1, so the
   answer reflects trunk and the agent's internal sub-queries do not
   cross over to the local delta. Falls through to do-api-command for
   the actual call."
  [{:keys [flags positional] :as parsed}]
  (when (and (not (:no-auto-federate flags))
             (api/launcher-setting :federation/auto-route true)
             (>= (count positional) 1))
    (let [hosted (api/active-connection)
          cwd    (canonicalize-path (first positional))
          ctx    (when hosted (api/detect-federation-context cwd hosted))]
      (when ctx
        (tui/eprintln (style/yellow
                       (str "Note: local HEAD diverges from trunk @"
                            (subs (:basis-sha ctx) 0 7)
                            "; ask sub-queries are trunk-only."))))))
  (do-api-command parsed))

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

(defn- parse-setting-value
  "Parse a setting value string into the most natural type. Falls back
   to the raw string when the input looks like an integer but overflows
   Long — the daemon's settings store accepts strings as-is, and a
   silent `nil` would have made the user's value disappear."
  [s]
  (cond
    (nil? s)                nil
    (= "true" s)            true
    (= "false" s)           false
    (re-matches #"-?\d+" s) (or (parse-long s) s)
    :else                   s))

(defn- do-settings [{:keys [flags positional]}]
  (let [k       (first positional)
        v       (second positional)
        n-args  (count positional)]
    (cond
      ;; Launcher-local settings live in ~/.noumenon/config.edn — never
      ;; round-tripped through the daemon.
      (and (>= n-args 1) (api/launcher-setting-key? k))
      (case n-args
        1 (let [val (api/launcher-setting (keyword k))]
            (if (some? val)
              (do (tui/eprintln (str "  " k ": " val)) 0)
              (do (tui/eprintln (str "No setting: " k)) 1)))
        2 (do (api/set-launcher-setting! (keyword k) (parse-setting-value v))
              (tui/eprintln (str "  " k " = " (parse-setting-value v))) 0))

      :else
      (let [conn (api/ensure-backend! flags)]
        (case n-args
          0 (print-api-result (api/get! conn "/api/settings"))
          1 (let [resp (api/get! conn (str "/api/settings/" k))]
              (if (:ok resp)
                (do (tui/eprintln (str "  " k ": " (:data resp))) 0)
                (let [all (api/get! conn "/api/settings")]
                  (if (:ok all)
                    (let [val (get (:data all) (keyword k))]
                      (if (some? val)
                        (do (tui/eprintln (str "  " k ": " val)) 0)
                        (do (tui/eprintln (str "No setting: " k)) 1)))
                    (print-api-result all)))))
          ;; Pre-parse the CLI string so daemon-side settings store the
          ;; natural type (CLI `noum settings retry/limit 5` becomes the
          ;; integer 5, not the string "5"). The daemon no longer
          ;; silently re-types string values, so the launcher does the
          ;; conversion that CLI users already expect — symmetric with
          ;; how launcher-local settings handle the same shape above.
          (print-api-result (api/post! conn "/api/settings"
                                       {:key k :value (parse-setting-value v)})))))))

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

(defn- do-delta-ensure [{:keys [flags] :as parsed}]
  (if-not (or (:basis-sha flags) (:basis_sha flags))
    (do (tui/eprintln "Usage: noum delta-ensure <repo> --basis-sha <sha>") 1)
    (do-api-command (assoc parsed :command "delta-ensure"))))

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
   "delta-ensure" do-delta-ensure
   "watch"      do-watch
   "status"     do-status
   "schema"     do-schema
   "delete"     do-delete
   "results"    do-results
   "compare"    do-compare
   "history"    do-history
   "open"       do-open
   "query"      do-query
   "ask"        do-ask
   "introspect" do-introspect
   "sessions"   do-sessions
   "feedback"   do-feedback
   "settings"   do-settings
   "connect"    do-connect
   "connections" do-connections
   "disconnect" do-disconnect})

(defn run-handler!
  "Invoke a command handler with the parsed input and return its exit
   code. Any uncaught exception becomes exit 1 with a clean error
   message — never a raw Clojure stack trace. Set NOUM_DEBUG=1 in the
   environment for the full trace when diagnosing a launcher bug."
  [handler parsed]
  (try
    (handler parsed)
    (catch Exception e
      (tui/eprintln (str (style/red "Error: ") (or (.getMessage e) (str (class e)))))
      (when (= "1" (System/getenv "NOUM_DEBUG"))
        (binding [*out* *err*]
          (.printStackTrace e)))
      (when-not (= "1" (System/getenv "NOUM_DEBUG"))
        (tui/eprintln (str (style/dim "  Set NOUM_DEBUG=1 for the full stack trace."))))
      1)))

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
        (System/exit (run-handler! handler parsed))))))
