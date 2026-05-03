(ns noumenon.daemon-control
  "Cross-process daemon lifecycle for callers that need a daemon to talk to
   but don't want to manage one. ensure-spawned! is a one-shot bootstrap:
   if a daemon is reachable it returns the existing connection; otherwise
   it forks a detached child JVM running the same code via the daemon
   subcommand and polls until that child is healthy.

   The spawned daemon outlives this process — that is the point. MCP
   bridges, ad-hoc CLI commands, and tests all share whichever daemon
   already exists."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.mcp.proxy :as proxy])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(def ^:private daemon-log
  (io/file (System/getProperty "user.home") ".noumenon" "daemon.log"))

(defn- tail-log [n]
  (try
    (->> (slurp daemon-log)
         str/split-lines
         (take-last n)
         (str/join "\n"))
    (catch Exception _ "")))

(defn- daemon-args
  "Argv for spawning a daemon child. Works for both uberjar runs (where
   classpath is one .jar) and dev `clj -M:run` runs (long classpath)."
  [{:keys [db-dir provider model]}]
  (let [java     (str (io/file (System/getProperty "java.home") "bin" "java"))
        cp       (System/getProperty "java.class.path")
        base     [java "-cp" cp "clojure.main" "-m" "noumenon.main"
                  "daemon" "--port" "0"]
        with-db  (cond-> base   db-dir   (into ["--db-dir" db-dir]))
        with-prv (cond-> with-db provider (into ["--provider" provider]))]
    (cond-> with-prv model (into ["--model" model]))))

(defn- spawn-daemon-jvm! [opts]
  (let [pb (ProcessBuilder. ^java.util.List (daemon-args opts))]
    (io/make-parents daemon-log)
    (.redirectOutput pb (ProcessBuilder$Redirect/appendTo daemon-log))
    (.redirectError pb (ProcessBuilder$Redirect/appendTo daemon-log))
    (.start pb)))

(defn ensure-spawned!
  "Return a connection map for a reachable daemon, spawning one if absent.
   Polls every 500ms up to 15s for the spawned daemon to become healthy.
   Throws ex-info on early child exit or timeout, with the last 30 lines
   of ~/.noumenon/daemon.log included in the message."
  [opts]
  (or (proxy/resolve-conn)
      (let [proc (spawn-daemon-jvm! opts)]
        (loop [attempts 30]
          (cond
            (proxy/resolve-conn)
            (proxy/resolve-conn)

            (not (.isAlive proc))
            (throw (ex-info (str "Daemon JVM exited (code " (.exitValue proc)
                                 ") before becoming healthy.\n\n"
                                 "Last lines of " daemon-log ":\n" (tail-log 30))
                            {:exit-code (.exitValue proc)}))

            (zero? attempts)
            (throw (ex-info (str "Daemon did not become healthy within 15 seconds.\n\n"
                                 "Last lines of " daemon-log ":\n" (tail-log 30))
                            {}))

            :else (do (Thread/sleep 500) (recur (dec attempts))))))))
