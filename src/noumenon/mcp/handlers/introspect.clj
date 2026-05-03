(ns noumenon.mcp.handlers.introspect
  "MCP tool handlers for the introspect cluster — synchronous run plus
   the asynchronous start/status/stop/history surface."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.db :as db]
            [noumenon.introspect :as introspect]
            [noumenon.llm :as llm]
            [noumenon.mcp.util :as mu]
            [noumenon.query :as query]
            [noumenon.sessions :as sessions]
            [noumenon.util :as util]))

(defn handle-introspect [args defaults]
  (mu/validate-llm-inputs! args)
  (mu/with-conn args defaults
    (fn [{:keys [db meta-conn db-name db-dir repo-path]}]
      (let [provider (or (args "provider") (:provider defaults))
            model    (or (args "model") (:model defaults))
            {:keys [invoke-fn]}
            (llm/make-messages-fn-from-opts
             {:provider provider :model model
              :temperature 0.7 :max-tokens 8192})
            invoke-fn-factory
            (fn []
              (:invoke-fn
               (llm/make-messages-fn-from-opts
                {:provider provider :model model
                 :temperature 0.0 :max-tokens 4096})))
            extra-repos (mu/resolve-extra-repos (args "extra_repos") db-dir)
            result (introspect/run-loop!
                    (cond-> {:db                  db
                             :repo-name           db-name
                             :repo-path           repo-path
                             :meta-conn           meta-conn
                             :invoke-fn-factory   invoke-fn-factory
                             :optimizer-invoke-fn invoke-fn
                             :max-iterations      (or (args "max_iterations") 10)
                             :max-hours           (args "max_hours")
                             :max-cost            (args "max_cost")
                             :eval-runs           (or (args "eval_runs") 1)
                             :git-commit?         (and (args "git_commit")
                                                       (not (:read-only defaults)))
                             :model-config        {:provider provider :model model}
                             :progress-fn         (:progress-fn defaults)}
                      (seq extra-repos)
                      (assoc :extra-repos extra-repos)
                      (args "target")
                      (assoc :allowed-targets
                             (set (map keyword (str/split (args "target") #","))))))]
        (mu/tool-result (str "Introspect complete: " (:improvements result)
                             " improvements in " (:iterations result)
                             " iterations (final score: "
                             (format "%.3f" (:final-score result))
                             ", run-id: " (:run-id result) ")"))))))

(defn handle-introspect-start [args defaults]
  (mu/validate-llm-inputs! args)
  (sessions/evict-stale!)
  (when (>= (sessions/running-count) sessions/max-sessions)
    (throw (ex-info "Too many active introspect sessions"
                    {:user-message (str "Maximum " sessions/max-sessions
                                        " concurrent sessions. Stop one first.")})))
  (mu/with-conn args defaults
    (fn [{:keys [db meta-conn db-name db-dir repo-path]}]
      (let [provider  (or (args "provider") (:provider defaults))
            model     (or (args "model") (:model defaults))
            stop-flag (atom false)
            {:keys [invoke-fn]}
            (llm/make-messages-fn-from-opts
             {:provider provider :model model :temperature 0.7 :max-tokens 8192})
            invoke-fn-factory
            (fn []
              (:invoke-fn
               (llm/make-messages-fn-from-opts
                {:provider provider :model model :temperature 0.0 :max-tokens 4096})))
            extra-repos (mu/resolve-extra-repos (args "extra_repos") db-dir)
            run-opts  (cond-> {:db db :repo-name db-name :repo-path repo-path
                               :meta-conn meta-conn
                               :invoke-fn-factory invoke-fn-factory
                               :optimizer-invoke-fn invoke-fn
                               :max-iterations (or (args "max_iterations") 10)
                               :max-hours (args "max_hours")
                               :max-cost (args "max_cost")
                               :eval-runs (or (args "eval_runs") 1)
                               :git-commit? (args "git_commit")
                               :model-config {:provider provider :model model}
                               :stop-flag stop-flag}
                        (seq extra-repos)
                        (assoc :extra-repos extra-repos)
                        (args "target")
                        (assoc :allowed-targets
                               (->> (str/split (args "target") #",")
                                    (keep (comp mu/allowed-introspect-targets keyword str/trim))
                                    set)))
            run-id    (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID))
            now       (System/currentTimeMillis)]
        (when (>= (sessions/running-count) sessions/max-sessions)
          (throw (ex-info "Too many active introspect sessions"
                          {:user-message (str "Maximum " sessions/max-sessions
                                              " concurrent sessions. Stop one first.")})))
        (sessions/register! run-id {:status :running :stop-flag stop-flag :started-at now})
        (let [progress-fn (fn [{:keys [current total message]}]
                            (sessions/update-session!
                             run-id #(assoc % :progress
                                            {:current current :total total :message message})))
              fut (future
                    (try
                      (let [result (introspect/run-loop!
                                    (assoc run-opts :run-id run-id :progress-fn progress-fn))
                            final-status (if @stop-flag :stopped :completed)]
                        (sessions/update-session! run-id
                                                  #(merge % {:status final-status :result result
                                                             :completed-at (System/currentTimeMillis)}))
                        result)
                      (catch Exception e
                        (sessions/update-session! run-id
                                                  #(merge % {:status :error :error (.getMessage e)
                                                             :completed-at (System/currentTimeMillis)})))))]
          (sessions/update-session! run-id #(assoc % :future fut))
          (mu/tool-result (str "Introspect started. Run ID: " run-id
                               "\nUse noumenon_introspect_status to check progress.")))))))

(defn handle-introspect-status [args _defaults]
  (let [run-id (args "run_id")]
    (util/validate-string-length! "run_id" run-id mu/max-run-id-len)
    (if-let [session (sessions/get-session run-id)]
      (let [{:keys [status result error started-at progress]} session]
        (mu/tool-result
         (case status
           :running   (let [elapsed-min (quot (- (System/currentTimeMillis) (or started-at 0))
                                              60000)
                            prog (when progress
                                   (str "\nIteration: " (:current progress) "/" (:total progress)
                                        "\nLast: " (:message progress)))]
                        (str "Status: running\nElapsed: " elapsed-min " minutes" prog))
           :completed (str "Status: completed\n" (sessions/format-result-summary result))
           :stopped   (str "Status: stopped (by request)\n"
                           (when result (sessions/format-result-summary result)))
           :error     (str "Status: error\n" error))))
      (mu/tool-error (str "Unknown run ID: " run-id)))))

(defn handle-introspect-stop [args _defaults]
  (let [run-id (args "run_id")]
    (util/validate-string-length! "run_id" run-id mu/max-run-id-len)
    (if-let [session (sessions/get-session run-id)]
      (case (:status session)
        :running   (do (reset! (:stop-flag session) true)
                       (mu/tool-result (str "Stop requested for " run-id
                                            ". Will halt after current iteration.")))
        :completed (mu/tool-result (str "Run " run-id " already completed."))
        :stopped   (mu/tool-result (str "Run " run-id " already stopped."))
        :error     (mu/tool-result (str "Run " run-id " already terminated with an error.")))
      (mu/tool-error (str "Unknown run ID: " run-id)))))

(defn handle-introspect-history [args defaults]
  (let [query-name (args "query_name")]
    (util/validate-string-length! "query_name" query-name 256)
    (when-not (str/starts-with? (str query-name) "introspect-")
      (throw (ex-info "Only introspect-* queries are available"
                      {:user-message "Use one of: introspect-runs, introspect-improvements, introspect-by-target, introspect-score-trend, introspect-failed-approaches, introspect-skipped"})))
    (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir defaults))
          meta-db   (d/db meta-conn)
          query-def (artifacts/load-named-query meta-db query-name)
          result    (query/run-named-query meta-db meta-db query-name)]
      (if (:ok result)
        (let [rows  (take (min (or (some-> (args "limit") long) 100) 1000)
                          (:ok result))
              total (count (:ok result))
              header (str "Query '" query-name "': " (count rows)
                          (when (> total (count rows)) (str " of " total))
                          " results"
                          (when-let [cols (:columns query-def)]
                            (str "\nColumns: " (str/join ", " cols)))
                          "\n")]
          (mu/tool-result (str header
                               (str/join "\n" (map pr-str rows))
                               (when (> total (count rows))
                                 (str "\n... truncated (" (- total (count rows)) " more rows)")))))
        (mu/tool-error (str "Query error: " (:error result)))))))
