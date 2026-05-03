(ns noum.daemon
  "Manage the Noumenon JVM daemon process."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [noum.paths :as paths]
            [noum.tui.core :as tui]
            [noum.tui.spinner :as spinner]))

(defn- read-daemon-info []
  (when (fs/exists? paths/daemon-file)
    (edn/read-string (slurp paths/daemon-file))))

(defn- process-alive? [pid]
  (try
    (zero? (:exit (proc/shell {:out :string :err :string}
                              "kill" "-0" (str pid))))
    (catch Exception _ false)))

(defn- healthy? [port]
  (try
    (= 200 (:status (http/get (str "http://127.0.0.1:" port "/health")
                              {:timeout 2000})))
    (catch Exception _ false)))

(defn running?
  "Check if the daemon is running and healthy."
  []
  (when-let [{:keys [port pid]} (read-daemon-info)]
    (and (process-alive? pid) (healthy? port))))

(defn connection
  "Return {:port N} if daemon is running, nil otherwise."
  []
  (when-let [{:keys [port pid]} (read-daemon-info)]
    (when (and (process-alive? pid) (healthy? port))
      {:port port})))

(defn- tail-log
  "Last n lines of the daemon log, or empty string if unreadable."
  [n]
  (try
    (->> (slurp paths/daemon-log)
         str/split-lines
         (take-last n)
         (str/join "\n"))
    (catch Exception _ "")))

(def ^:private lock-file
  (str (fs/path paths/data-dir "noumenon" "noumenon-internal" ".lock")))

(defn- lock-holder
  "Return \"PID N (cmdline)\" if any process holds the Datomic lock, nil
   otherwise. Uses lsof + ps; both work identically on macOS and Linux.
   Silently returns nil if either tool is missing or the lock is free."
  []
  (try
    (let [{:keys [exit out]} (proc/shell {:out :string :err :string :continue true}
                                         "lsof" "-Fp" lock-file)]
      (when (zero? exit)
        (when-let [pid (some->> (str/split-lines out)
                                (some #(when (str/starts-with? % "p") (subs % 1))))]
          (let [cmd (->> (proc/shell {:out :string :err :string :continue true}
                                     "ps" "-p" pid "-o" "command=")
                         :out
                         str/trim)
                short-cmd (if (> (count cmd) 120)
                            (str (subs cmd 0 60) " … " (subs cmd (- (count cmd) 60)))
                            cmd)]
            (str "PID " pid (when (seq short-cmd) (str " (" short-cmd ")")))))))
    (catch Exception _ nil)))

(defn- failure-message [headline]
  (str headline
       (when-let [h (lock-holder)]
         (str "\nLock currently held by " h "."))
       "\n\nLast lines of " paths/daemon-log ":\n"
       (tail-log 30)))

(defn start!
  "Start the JVM daemon. Returns {:port N} or throws."
  [{:keys [jre-path jar-path db-dir provider model token]}]
  (if (running?)
    (let [{:keys [port]} (read-daemon-info)]
      (tui/eprintln (str "Daemon already running on port " port))
      {:port port})
    (let [java-bin (str (fs/path jre-path "bin" "java"))
          args     (cond-> [java-bin "-jar" jar-path "daemon" "--port" "0"]
                     db-dir   (into ["--db-dir" db-dir])
                     provider (into ["--provider" provider])
                     model    (into ["--model" model]))
          env      (cond-> (into {} (System/getenv))
                     token (assoc "NOUMENON_TOKEN" token))
          s        (spinner/start "Starting Noumenon daemon...")
          jvm      (:proc (proc/process {:cmd args
                                         :env env
                                         :out [:append paths/daemon-log]
                                         :err [:append paths/daemon-log]}))]
      (loop [attempts 0]
        (Thread/sleep 500)
        (cond
          (not (.isAlive jvm))
          (do ((:stop s) "Daemon failed to start")
              (throw (ex-info (failure-message
                               (str "Daemon JVM exited (code "
                                    (.exitValue jvm)
                                    ") before becoming healthy."))
                              {:exit-code (.exitValue jvm)})))

          (> attempts 60)
          (do (.destroy jvm)
              ((:stop s) "Daemon failed to start")
              (throw (ex-info (failure-message
                               "Daemon failed to start within 30 seconds.")
                              {})))

          (running?)
          (let [{:keys [port]} (read-daemon-info)]
            ((:stop s) (str "Daemon running on port " port))
            {:port port})

          :else (recur (inc attempts)))))))

(defn- wait-for-exit
  "Poll process-alive? every 500ms up to (timeout-ms/500) attempts. Returns
   true if the process is gone within the deadline, false if still alive."
  [pid timeout-ms]
  (loop [attempts (quot timeout-ms 500)]
    (cond
      (not (process-alive? pid)) true
      (zero? attempts)           false
      :else (do (Thread/sleep 500) (recur (dec attempts))))))

(defn stop!
  "Stop the daemon recorded in daemon.edn. SIGTERM, wait up to 5s, then
   SIGKILL with another 2s wait. Only delete daemon.edn after the process
   is confirmed gone — leaving the file in place lets the next stop! see
   an unkillable daemon, rather than orphaning a JVM with no record."
  []
  (if-let [{:keys [pid]} (read-daemon-info)]
    (do
      (try (proc/shell {:out :string :err :string} "kill" (str pid))
           (catch Exception _))
      (cond
        (wait-for-exit pid 5000)
        (do (fs/delete-if-exists paths/daemon-file)
            (tui/eprintln (str "Daemon stopped (PID " pid ").")))

        :else
        (do
          (try (proc/shell {:out :string :err :string} "kill" "-9" (str pid))
               (catch Exception _))
          (if (wait-for-exit pid 2000)
            (do (fs/delete-if-exists paths/daemon-file)
                (tui/eprintln (str "Daemon force-killed (PID " pid ").")))
            (throw (ex-info (str "Daemon (PID " pid ") refused to die after SIGKILL. "
                                 "Leaving daemon.edn in place for inspection.")
                            {:pid pid}))))))
    (tui/eprintln "No managed daemon to stop.")))

(defn ensure!
  "Ensure the daemon is running. Start it if not. Returns {:port N}."
  [opts]
  (or (connection) (start! opts)))

(defn api-url
  "Build an API URL for the daemon."
  [port path]
  (str "http://127.0.0.1:" port path))
