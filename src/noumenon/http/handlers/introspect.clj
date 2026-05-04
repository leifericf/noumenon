(ns noumenon.http.handlers.introspect
  "HTTP handlers for the introspect cluster — synchronous and SSE-streamed
   run, plus status/stop/history."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.git :as git]
            [noumenon.http.middleware :as mw]
            [noumenon.introspect :as introspect]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.repo :as repo]
            [noumenon.sessions :as sessions]
            [noumenon.util :as util]))

;; resolve-extra-repos lifted to noumenon.repo so CLI and HTTP share
;; one implementation. The wrapper here forwards to it with HTTP's
;; lookup-uri-fn so db-name lookups go through the cached middleware path.

(defn- resolve-extra-repos
  [extra-repos-str db-dir]
  (repo/resolve-extra-repos extra-repos-str db-dir mw/lookup-repo-uri))

(defn- build-introspect-opts [{:keys [db meta-conn db-name db-dir repo-path]} params config
                              {:keys [stop-flag run-id progress-fn]}]
  (let [{:keys [provider model]} (mw/resolve-provider params config)
        {:keys [invoke-fn]}
        (llm/make-messages-fn-from-opts
         {:provider provider :model model :temperature 0.7 :max-tokens 8192})
        invoke-fn-factory
        (fn []
          (:invoke-fn
           (llm/make-messages-fn-from-opts
            {:provider provider :model model :temperature 0.0 :max-tokens 4096})))
        extra-repos (resolve-extra-repos (:extra_repos params) db-dir)]
    (cond-> {:db db :repo-name db-name :repo-path repo-path
             :meta-conn meta-conn
             :invoke-fn-factory invoke-fn-factory
             :optimizer-invoke-fn invoke-fn
             :max-iterations (or (:max_iterations params) 10)
             :max-hours (:max_hours params)
             :max-cost (:max_cost params)
             :eval-runs (or (:eval_runs params) 1)
             :git-commit? (and (:git_commit params)
                               (not (:read-only config))
                               (not (git/bare-repo? repo-path)))
             :model-config {:provider provider :model model}
             :progress-fn progress-fn}
      stop-flag          (assoc :stop-flag stop-flag)
      run-id             (assoc :run-id run-id)
      (seq extra-repos)  (assoc :extra-repos extra-repos)
      (:target params)
      (assoc :allowed-targets
             (->> (str/split (:target params) #",")
                  (keep (comp util/valid-introspect-targets keyword str/trim))
                  set)))))

(defn handle-introspect [request config]
  (let [params (mw/parse-json-body request)]
    (util/validate-introspect-targets! (:target params))
    (sessions/evict-stale!)
    (when (>= (sessions/running-count) sessions/max-sessions)
      (throw (ex-info "Too many active introspect sessions"
                      {:status 429
                       :message (str "Maximum " sessions/max-sessions
                                     " concurrent sessions. Stop one first.")})))
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (let [stop-flag (atom false)
              run-id    (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID))
              now       (System/currentTimeMillis)
              run-opts  (build-introspect-opts ctx params config
                                               {:stop-flag stop-flag :run-id run-id})]
          (when (>= (sessions/running-count) sessions/max-sessions)
            (throw (ex-info "Too many active introspect sessions"
                            {:status 429
                             :message (str "Maximum " sessions/max-sessions
                                           " concurrent sessions. Stop one first.")})))
          (sessions/register! run-id {:status :running :stop-flag stop-flag :started-at now})
          (if (mw/wants-sse? request)
            (mw/with-sse request
              (fn [progress-fn]
                (try
                  (let [result (introspect/run-loop! (assoc run-opts :progress-fn progress-fn))
                        final  (if @stop-flag :stopped :completed)]
                    (sessions/update-session!
                     run-id #(merge % {:status final :result result
                                       :completed-at (System/currentTimeMillis)}))
                    (assoc result :run-id run-id))
                  (catch Exception e
                    (sessions/update-session!
                     run-id #(merge % {:status :error :error (.getMessage e)
                                       :completed-at (System/currentTimeMillis)}))
                    (throw e)))))
            (try
              (let [result (introspect/run-loop! run-opts)
                    final  (if @stop-flag :stopped :completed)]
                (sessions/update-session!
                 run-id #(merge % {:status final :result result
                                   :completed-at (System/currentTimeMillis)}))
                (mw/ok (assoc result :run-id run-id)))
              (catch Exception e
                (sessions/update-session!
                 run-id #(merge % {:status :error :error (.getMessage e)
                                   :completed-at (System/currentTimeMillis)}))
                (throw e)))))))))

(defn handle-introspect-status [request _config]
  (let [params (merge (mw/parse-json-body request) (:query-params request))
        run-id (:run_id params)]
    (if-let [session (sessions/get-session run-id)]
      (let [{:keys [status result error started-at]} session]
        (mw/ok (cond-> {:status (name status)}
                 (= :running status)
                 (assoc :elapsed-min (quot (- (System/currentTimeMillis) (or started-at 0)) 60000))
                 result (assoc :improvements (:improvements result)
                               :iterations (:iterations result)
                               :final-score (:final-score result))
                 error (assoc :error error))))
      (mw/error-response 404 (str "Unknown run ID: " run-id)))))

(defn handle-introspect-stop [request _config]
  (let [params (merge (mw/parse-json-body request) (:query-params request))
        run-id (:run_id params)]
    (if-let [session (sessions/get-session run-id)]
      (case (:status session)
        :running   (do (reset! (:stop-flag session) true)
                       (mw/ok {:stopped run-id}))
        :completed (mw/ok {:message (str "Run " run-id " already completed.")})
        :stopped   (mw/ok {:message (str "Run " run-id " already stopped.")})
        :error     (mw/ok {:message (str "Run " run-id " already terminated with an error.")}))
      (mw/error-response 404 (str "Unknown run ID: " run-id)))))

(defn handle-introspect-history [request config]
  (let [params     (merge (mw/parse-json-body request) (:query-params request))
        query-name (or (:query_name params)
                       (get-in request [:params :query]))]
    (mw/validate-string-length! "query_name" (str query-name) mw/max-run-id-len)
    (when-not (or (str/starts-with? (str query-name) "introspect-")
                  (str/starts-with? (str query-name) "ask-"))
      (throw (ex-info "Only introspect-* and ask-* queries"
                      {:status 400 :message "Only introspect-* and ask-* queries"})))
    (let [meta-conn (db/ensure-meta-db (:db-dir config))
          meta-db   (d/db meta-conn)
          result    (query/run-named-query meta-db meta-db query-name)]
      (if (:ok result)
        (mw/ok {:query query-name :results (take 100 (:ok result))})
        (mw/error-response 400 (:error result))))))
