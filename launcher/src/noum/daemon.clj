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

(defn stop!
  "Stop the running daemon."
  []
  (when-let [{:keys [pid]} (read-daemon-info)]
    (try
      (proc/shell "kill" (str pid))
      (tui/eprintln "Daemon stopped.")
      (catch Exception _
        (tui/eprintln "Daemon process not found.")))
    (when (fs/exists? paths/daemon-file)
      (fs/delete paths/daemon-file))))

(defn ensure!
  "Ensure the daemon is running. Start it if not. Returns {:port N}."
  [opts]
  (or (connection) (start! opts)))

(defn api-url
  "Build an API URL for the daemon."
  [port path]
  (str "http://127.0.0.1:" port path))
