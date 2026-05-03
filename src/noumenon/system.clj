(ns noumenon.system
  "Minimal Integrant lifecycle. Existing subsystems keep their
   accessor APIs (db/get-or-create-conn, embed/get-cached-index,
   sessions/register!, …). Integrant only owns init/halt for the
   underlying state, so the daemon can shut down without leaking
   Datomic connections, semaphore handles, or running futures."
  (:require [integrant.core :as ig]
            [noumenon.concurrency :as cc]
            [noumenon.db :as db]
            [noumenon.embed :as embed]
            [noumenon.http :as http]
            [noumenon.http.handlers.query :as h-query]
            [noumenon.sessions :as sessions]
            [noumenon.util :as util :refer [log!]]))

;; --- Datomic connections ---

(defmethod ig/init-key :noumenon/datomic-conns [_ _]
  ;; Connections are created lazily by db/get-or-create-conn. The
  ;; component value is the cache atom itself so halt-key! can find it.
  db/conn-cache)

(defmethod ig/halt-key! :noumenon/datomic-conns [_ _cache]
  (db/release-conns!))

;; --- LLM semaphore ---

(defmethod ig/init-key :noumenon/llm-semaphore [_ {:keys [max-permits]}]
  (cc/init-llm-semaphore! max-permits)
  {:max-permits max-permits})

(defmethod ig/halt-key! :noumenon/llm-semaphore [_ _]
  (cc/release!))

;; --- In-memory caches and session stores ---

(defmethod ig/init-key :noumenon/embed-cache [_ _] :ready)
(defmethod ig/halt-key! :noumenon/embed-cache [_ _] (embed/release-cache!))

(defmethod ig/init-key :noumenon/completion-cache [_ _] :ready)
(defmethod ig/halt-key! :noumenon/completion-cache [_ _] (h-query/release-completion-cache!))

(defmethod ig/init-key :noumenon/agent-sessions [_ _] :ready)
(defmethod ig/halt-key! :noumenon/agent-sessions [_ _] (sessions/release!))

;; --- HTTP server ---

(defmethod ig/init-key :noumenon/http-server [_ opts]
  (let [port (http/start! opts)]
    (log! (str "system: HTTP daemon on port " port))
    {:port port}))

(defmethod ig/halt-key! :noumenon/http-server [_ _]
  (http/stop!))

;; --- System config ---

(defn config
  "Build the Integrant config map for a daemon process. opts mirrors the
   shape passed to http/start! plus :max-llm-concurrency for the LLM
   semaphore."
  [{:keys [port bind db-dir provider model token max-llm-concurrency]}]
  (let [max-permits (or max-llm-concurrency
                        (util/env-int "NOUMENON_MAX_LLM_CONCURRENCY")
                        10)]
    {:noumenon/datomic-conns    {}
     :noumenon/llm-semaphore    {:max-permits max-permits}
     :noumenon/embed-cache      {}
     :noumenon/completion-cache {}
     :noumenon/agent-sessions   {}
     :noumenon/http-server      (cond-> {:port (or port 0) :bind (or bind "127.0.0.1")}
                                  db-dir   (assoc :db-dir db-dir)
                                  provider (assoc :provider provider)
                                  model    (assoc :model model)
                                  token    (assoc :token token))}))

(defn init
  "Build and start the daemon system. Returns the running system map."
  [opts]
  (ig/init (config opts)))

(defn halt!
  "Halt every component in reverse-dep order."
  [system]
  (ig/halt! system))
