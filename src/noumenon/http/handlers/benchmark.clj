(ns noumenon.http.handlers.benchmark
  "HTTP handlers for the benchmark cluster — run, results, compare."
  (:require [datomic.client.api :as d]
            [noumenon.benchmark :as bench]
            [noumenon.http.middleware :as mw]
            [noumenon.llm :as llm]))

(defn- run-benchmark [{:keys [conn meta-db db db-dir db-name repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn]}
        (llm/wrap-as-prompt-fn-from-opts (mw/resolve-provider params config))
        layers (mw/validate-layers (:layers params))
        mode   (cond-> {} layers (assoc :layers layers))
        result (bench/run-benchmark! db repo-path prompt-fn
                                     :meta-db meta-db :conn conn :mode mode
                                     :budget {:max-questions (:max_questions params)}
                                     :report? (:report params)
                                     :concurrency 3
                                     :progress-fn progress-fn
                                     :db-dir db-dir :db-name db-name)]
    (select-keys result [:run-id :aggregate :stop-reason :report-path])))

(defn handle-benchmark-run [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (if (mw/wants-sse? request)
          (mw/with-sse request (partial run-benchmark ctx params config))
          (mw/ok (run-benchmark ctx params config nil)))))))

(defn handle-benchmark-results [request config]
  (let [params (merge (mw/parse-json-body request)
                      (:query-params request))
        repo   (or (:repo_path params) (get-in request [:params :repo]))]
    (mw/with-repo {:repo_path repo} (:db-dir config)
      (fn [{:keys [db]}]
        (let [run-id (:run_id params)
              _      (when run-id (mw/validate-string-length! "run_id" run-id mw/max-run-id-len))
              runs   (if run-id
                       (d/q '[:find (pull ?r [*]) :in $ ?id
                              :where [?r :bench.run/id ?id]]
                            db run-id)
                       (d/q '[:find (pull ?r [*])
                              :where [?r :bench.run/id _]]
                            db))
              run    (->> runs (map first) (sort-by :bench.run/started-at) last)]
          (if run
            (mw/ok run)
            (mw/error-response 404 "No benchmark runs found.")))))))

(defn handle-benchmark-compare [request config]
  (let [params (merge (mw/parse-json-body request)
                      (:query-params request))
        repo   (or (:repo_path params) (get-in request [:params :repo]))]
    (mw/with-repo {:repo_path repo} (:db-dir config)
      (fn [{:keys [db]}]
        (let [_       (mw/validate-string-length! "run_id_a" (:run_id_a params) mw/max-run-id-len)
              _       (mw/validate-string-length! "run_id_b" (:run_id_b params) mw/max-run-id-len)
              pull-run (fn [id]
                         (ffirst (d/q '[:find (pull ?r [*]) :in $ ?id
                                        :where [?r :bench.run/id ?id]]
                                      db id)))
              run-a (pull-run (:run_id_a params))
              run-b (pull-run (:run_id_b params))]
          (cond
            (not run-a) (mw/error-response 404 (str "Run not found: " (:run_id_a params)))
            (not run-b) (mw/error-response 404 (str "Run not found: " (:run_id_b params)))
            :else       (mw/ok (bench/compare-runs run-a run-b))))))))
