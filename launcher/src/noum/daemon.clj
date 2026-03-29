(ns noum.daemon
  "Manage the Noumenon JVM daemon process."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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
                     model    (into ["--model" model])
                     token    (into ["--token" token]))
          s        (spinner/start "Starting Noumenon daemon...")
          _        (proc/process {:cmd args
                                  :out (io/writer paths/daemon-log :append true)
                                  :err (io/writer paths/daemon-log :append true)})]
      (loop [attempts 0]
        (Thread/sleep 500)
        (cond
          (> attempts 60)
          (do ((:stop s) "Daemon failed to start")
              (throw (ex-info "Daemon failed to start within 30 seconds" {})))

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
