(ns noumenon.http.handlers.pipeline
  "HTTP handlers that mutate the knowledge graph — the core import →
   analyze → enrich → synthesize → digest pipeline plus the trunk
   `update` and the `delta/ensure` materializer."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.benchmark :as bench]
            [noumenon.delta :as delta]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.http.middleware :as mw]
            [noumenon.imports :as imports]
            [noumenon.llm :as llm]
            [noumenon.sync :as sync]
            [noumenon.synthesize :as synthesize]
            [noumenon.util :as util]))

(defn- run-import [{:keys [conn repo-path]} progress-fn]
  (let [git-r   (git/import-commits! conn repo-path repo-path progress-fn)
        files-r (files/import-files! conn repo-path repo-path)]
    {:commits-imported (:commits-imported git-r)
     :commits-skipped  (:commits-skipped git-r)
     :files-imported   (:files-imported files-r)
     :files-skipped    (:files-skipped files-r)
     :dirs-imported    (:dirs-imported files-r)}))

(defn handle-import [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (if (mw/wants-sse? request)
          (mw/with-sse request (partial run-import ctx))
          (mw/ok (run-import ctx nil)))))))

(defn- run-analyze [{:keys [conn meta-db repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn model-id provider-kw]}
        (llm/wrap-as-prompt-fn-from-opts (mw/resolve-provider params config))
        concurrency (min (or (:concurrency params) 3) 20)
        selector    (select-keys params [:path :include :exclude :lang])
        result      (analyze/analyze-repo! conn repo-path prompt-fn
                                           (cond-> (assoc selector
                                                          :meta-db meta-db
                                                          :model-id model-id
                                                          :provider (name provider-kw)
                                                          :concurrency concurrency
                                                          :no-promote? (boolean (:no_promote params))
                                                          :progress-fn progress-fn)
                                             (:max_files params)
                                             (assoc :max-files (:max_files params))))]
    (select-keys result [:files-analyzed :files-promoted :files-skipped
                         :files-errored :files-parse-errored
                         :total-usage])))

(defn handle-analyze [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (if (mw/wants-sse? request)
          (mw/with-sse request (partial run-analyze ctx params config))
          (mw/ok (run-analyze ctx params config nil)))))))

(defn- run-enrich [{:keys [conn repo-path]} params progress-fn]
  (let [concurrency (min (or (:concurrency params) 8) 20)
        selector    (select-keys params [:path :include :exclude :lang])
        result      (imports/enrich-repo! conn repo-path (assoc selector
                                                                :concurrency concurrency
                                                                :progress-fn progress-fn))]
    (select-keys result [:files-processed :imports-resolved])))

(defn handle-enrich [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (if (mw/wants-sse? request)
          (mw/with-sse request (partial run-enrich ctx params))
          (mw/ok (run-enrich ctx params nil)))))))

(defn handle-delta-ensure [request _config]
  (let [params    (mw/parse-json-body request)
        repo-path (:repo_path params)
        basis-sha (:basis_sha params)
        branch    (:branch params)]
    (when-not (and repo-path basis-sha)
      (throw (ex-info "Missing repo_path or basis_sha"
                      {:status 400 :message "repo_path and basis_sha are required"})))
    (when-not (sync/valid-sha? basis-sha)
      (throw (ex-info "Invalid basis_sha"
                      {:status 400 :message "basis_sha must be a 40-char lowercase hex SHA"})))
    (when-let [reason (util/validate-repo-path repo-path)]
      (throw (ex-info reason {:status 400 :message (str "repo_path " reason)})))
    (mw/validate-string-length! "branch" branch mw/max-branch-name-len)
    (mw/validate-string-length! "parent_host" (:parent_host params) mw/max-host-len)
    (mw/validate-string-length! "parent_db_name" (:parent_db_name params) mw/max-db-name-len)
    (let [delta-opts (cond-> {}
                       branch                     (assoc :branch-name branch)
                       (:parent_host params)      (assoc :parent-host (:parent_host params))
                       (:parent_db_name params)   (assoc :parent-db-name (:parent_db_name params)))
          conn       (delta/ensure-delta-db! repo-path basis-sha delta-opts)
          result     (delta/update-delta! conn repo-path basis-sha delta-opts)]
      (mw/ok result))))

(defn handle-update [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [{:keys [conn meta-db repo-path]}]
        (let [opts (if (:analyze params)
                     (let [{:keys [prompt-fn model-id provider-kw]}
                           (llm/wrap-as-prompt-fn-from-opts
                            (mw/resolve-provider params config))]
                       (assoc (select-keys params [:path :include :exclude :lang])
                              :concurrency 8 :analyze? true
                              :meta-db meta-db :model-id model-id
                              :provider (name provider-kw) :invoke-llm prompt-fn))
                     (assoc (select-keys params [:path :include :exclude :lang])
                            :concurrency 8))
              result (sync/update-repo! conn repo-path repo-path opts)]
          (mw/ok result))))))

(defn- run-synthesize [{:keys [conn meta-db db-name]} params config progress-fn]
  (let [{:keys [prompt-fn model-id provider-kw]}
        (llm/wrap-as-prompt-fn-from-opts (mw/resolve-provider params config))]
    (when progress-fn (progress-fn {:current 0 :total 0 :message "Synthesizing architecture..."}))
    (synthesize/synthesize-repo! conn prompt-fn
                                 {:meta-db meta-db :provider (name provider-kw)
                                  :model-id model-id :repo-name db-name})))

(defn handle-synthesize [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (if (mw/wants-sse? request)
          (mw/with-sse request (partial run-synthesize ctx params config))
          (mw/ok (run-synthesize ctx params config nil)))))))

(defn- run-digest [{:keys [conn meta-db db-dir db-name repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn model-id provider-kw]}
        (llm/wrap-as-prompt-fn-from-opts (mw/resolve-provider params config))
        step-progress (fn [step-name inner-fn]
                        (when progress-fn
                          (progress-fn {:current 0 :total 0 :message (str "digest: " step-name "...")}))
                        (inner-fn))
        step-fn     (fn [step-name]
                      (when progress-fn
                        (fn [evt] (progress-fn (assoc evt :step step-name)))))
        selector  (select-keys params [:path :include :exclude :lang])
        update-r  (when-not (or (:skip_import params) (:skip_enrich params))
                    (step-progress "update"
                                   #(sync/update-repo! conn repo-path repo-path
                                                       (assoc selector :concurrency 8))))
        analyze-r (when-not (:skip_analyze params)
                    (step-progress "analyze"
                                   #(let [r (analyze/analyze-repo! conn repo-path prompt-fn
                                                                   (assoc selector
                                                                          :meta-db meta-db :model-id model-id
                                                                          :provider (name provider-kw)
                                                                          :concurrency 3
                                                                          :no-promote? (boolean (:no_promote params))
                                                                          :progress-fn (step-fn "analyze")))]
                                      (select-keys r [:files-analyzed :files-promoted :total-usage]))))
        synth-r   (when-not (:skip_synthesize params)
                    (step-progress "synthesize"
                                   #(synthesize/synthesize-repo!
                                     conn prompt-fn
                                     {:meta-db meta-db :provider (name provider-kw) :model-id model-id
                                      :repo-name (last (str/split repo-path #"/"))})))
        bench-r   (when-not (:skip_benchmark params)
                    (step-progress "benchmark"
                                   #(let [db     (d/db conn)
                                          layers (mw/validate-layers (:layers params))
                                          mode   (cond-> {} layers (assoc :layers layers))
                                          r      (bench/run-benchmark! db repo-path prompt-fn
                                                                       :meta-db meta-db :conn conn :mode mode
                                                                       :budget {:max-questions (:max_questions params)}
                                                                       :report? (:report params)
                                                                       :concurrency 3
                                                                       :progress-fn (step-fn "benchmark")
                                                                       :db-dir db-dir :db-name db-name)]
                                      (select-keys r [:run-id :aggregate :stop-reason :report-path]))))]
    (cond-> {}
      update-r  (assoc :update update-r)
      analyze-r (assoc :analyze analyze-r)
      synth-r   (assoc :synthesize synth-r)
      bench-r   (assoc :benchmark bench-r))))

(defn handle-digest [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (if (mw/wants-sse? request)
          (mw/with-sse request (partial run-digest ctx params config))
          (mw/ok (run-digest ctx params config nil)))))))
