(ns noumenon.cli.commands.daemon
  "CLI command handlers for long-running processes — the MCP server and
   the HTTP daemon. The daemon boots through noumenon.system so that
   process exit walks the Integrant halt graph (HTTP server → caches →
   Datomic connections)."
  (:require [noumenon.daemon-control :as daemon-control]
            [noumenon.mcp :as mcp]
            [noumenon.system :as system]
            [noumenon.util :as util :refer [log!]]))

(defn resolve-db-dir
  "Resolve the storage directory the daemon should use, in precedence order:
   --db-dir flag > NOUMENON_DB_DIR env > <user-home>/.noumenon/data.

   The env-var step closes a gap where the CLI's hardcoded user-home
   fallback fired before noumenon.http.server/resolve-server-config's
   own env-var lookup ever ran — silently dropping NOUMENON_DB_DIR for
   `noum daemon` and `noum serve`."
  [{:keys [db-dir]}]
  (or db-dir
      (util/env "NOUMENON_DB_DIR")
      (str (System/getProperty "user.home") "/.noumenon/data")))

(defn do-serve
  "Start the MCP server. Ensures a daemon is reachable first — spawning
   one if absent — so the bridge can proxy every tool call instead of
   opening its own Datomic conn and racing the daemon for the lock."
  [parsed]
  (let [opts (assoc parsed :db-dir (resolve-db-dir parsed))]
    (daemon-control/ensure-spawned! opts)
    (mcp/serve! opts)
    {:exit 0}))

(defn do-daemon
  "Start the HTTP daemon under Integrant supervision."
  [parsed]
  (let [daemon-db-dir (resolve-db-dir parsed)
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
                      (Thread. ^Runnable
                       #(let [done (future (system/halt! sys))]
                          (deref done 5000 :timeout))))
    @(promise)
    {:exit 0}))
