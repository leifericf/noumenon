(ns noumenon.cli.commands.daemon
  "CLI command handlers for long-running processes — the MCP server and
   the HTTP daemon."
  (:require [noumenon.http :as http]
            [noumenon.mcp :as mcp]
            [noumenon.util :refer [log!]]))

(defn do-serve
  "Start the MCP server."
  [parsed]
  (let [user-db (str (System/getProperty "user.home") "/.noumenon/data")]
    (mcp/serve! (update parsed :db-dir #(or % user-db)))
    {:exit 0}))

(defn do-daemon
  "Start the HTTP daemon."
  [parsed]
  (let [daemon-db-dir (or (:db-dir parsed)
                          (str (System/getProperty "user.home") "/.noumenon/data"))
        port (http/start! {:port     (:port parsed 0)
                           :bind     (:bind parsed "127.0.0.1")
                           :db-dir   daemon-db-dir
                           :provider (:provider parsed)
                           :model    (:model parsed)
                           :token    (:token parsed)})]
    (log! (str "Daemon running on port " port ". Press Ctrl+C to stop."))
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable http/stop!))
    @(promise)
    {:exit 0}))
