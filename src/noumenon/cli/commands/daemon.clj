(ns noumenon.cli.commands.daemon
  "CLI command handlers for long-running processes — the MCP server and
   the HTTP daemon. The daemon boots through noumenon.system so that
   process exit walks the Integrant halt graph (HTTP server → caches →
   Datomic connections)."
  (:require [noumenon.mcp :as mcp]
            [noumenon.system :as system]
            [noumenon.util :refer [log!]]))

(defn do-serve
  "Start the MCP server."
  [parsed]
  (let [user-db (str (System/getProperty "user.home") "/.noumenon/data")]
    (mcp/serve! (update parsed :db-dir #(or % user-db)))
    {:exit 0}))

(defn do-daemon
  "Start the HTTP daemon under Integrant supervision."
  [parsed]
  (let [daemon-db-dir (or (:db-dir parsed)
                          (str (System/getProperty "user.home") "/.noumenon/data"))
        sys (system/init {:port     (:port parsed 0)
                          :bind     (:bind parsed "127.0.0.1")
                          :db-dir   daemon-db-dir
                          :provider (:provider parsed)
                          :model    (:model parsed)
                          :token    (:token parsed)
                          :max-llm-concurrency (:max-llm-concurrency parsed 10)})]
    (log! (str "Daemon running on port " (get-in sys [:noumenon/http-server :port])
               ". Press Ctrl+C to stop."))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(system/halt! sys)))
    @(promise)
    {:exit 0}))
