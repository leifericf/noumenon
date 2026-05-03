(ns noumenon.cli.commands.digest
  "CLI command handler for `digest` — runs the full pipeline (import →
   enrich → analyze → calls → synthesize → embed → benchmark) with
   per-step skip flags."
  (:require [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.benchmark :as bench]
            [noumenon.calls :as calls]
            [noumenon.cli.util :as cu]
            [noumenon.db :as db]
            [noumenon.embed :as embed]
            [noumenon.llm :as llm]
            [noumenon.sync :as sync]
            [noumenon.synthesize :as synthesize]
            [noumenon.util :refer [log!]]))

(defn- run-digest-step!
  "Run one digest step with timing. Stores result in results atom under key."
  [results step-key label run-fn]
  (log! (str "digest: " label "..."))
  (let [start (System/currentTimeMillis)
        r     (run-fn)]
    (log! (str "digest: " label " done (" (- (System/currentTimeMillis) start) " ms)"))
    (swap! results assoc step-key r)))

(defn do-digest
  "Run the full pipeline: import → enrich → analyze → synthesize → embed → benchmark.
   Each step is idempotent and can be skipped with --skip-* flags."
  [{:keys [skip-import skip-enrich skip-analyze skip-synthesize skip-benchmark
           model provider concurrency max-questions layers report] :as opts}]
  (cu/with-valid-repo
    (update opts :repo-path cu/resolve-repo-path)
    (fn [{:keys [repo-path db-dir db-name]}]
      (try
        (let [conn      (db/connect-and-ensure-schema db-dir db-name)
              meta-conn (db/ensure-meta-db db-dir)
              _         (artifacts/reseed! meta-conn)
              meta-db   (d/db meta-conn)
              repo-uri  (.getCanonicalPath (java.io.File. (str repo-path)))
              needs-llm (not (and skip-analyze skip-synthesize skip-benchmark))
              {:keys [prompt-fn model-id provider-kw]}
              (when needs-llm
                (llm/wrap-as-prompt-fn-from-opts {:provider provider :model model}))
              selector  (select-keys opts [:path :include :exclude :lang])
              results   (atom {})
              t0        (System/currentTimeMillis)]
          (when-not (and skip-import skip-enrich)
            (run-digest-step! results :update "import + enrich"
                              #(sync/update-repo! conn repo-path repo-uri
                                                  (assoc selector :concurrency (or concurrency 8)))))
          (when-not skip-analyze
            (run-digest-step! results :analyze "analyze"
                              #(analyze/analyze-repo! conn repo-path prompt-fn
                                                      (assoc selector
                                                             :meta-db meta-db
                                                             :model-id model-id
                                                             :provider (some-> provider-kw name)
                                                             :concurrency (or concurrency 3))))
            (run-digest-step! results :calls "resolve calls"
                              #(calls/resolve-calls! conn)))
          (when-not skip-synthesize
            (let [synth-llm (llm/wrap-as-prompt-fn-from-opts
                             {:provider provider :model model :max-tokens 16384})]
              (run-digest-step! results :synthesize "synthesize"
                                #(synthesize/synthesize-repo!
                                  conn (:prompt-fn synth-llm)
                                  {:meta-db   meta-db
                                   :provider  (some-> (:provider-kw synth-llm) name)
                                   :model-id  (:model-id synth-llm)
                                   :repo-name db-name}))))
          (run-digest-step! results :embed "embed"
                            #(let [db (d/db conn)]
                               (embed/build-index! db db-dir db-name)))
          (when-not skip-benchmark
            (run-digest-step! results :benchmark "benchmark"
                              #(let [db   (d/db conn)
                                     mode (cond-> {} layers (assoc :layers layers))]
                                 (select-keys
                                  (bench/run-benchmark! db repo-path prompt-fn
                                                        :meta-db meta-db
                                                        :conn conn :mode mode
                                                        :budget {:max-questions max-questions}
                                                        :report? report
                                                        :concurrency (or concurrency 3)
                                                        :db-dir db-dir :db-name db-name)
                                  [:run-id :aggregate :stop-reason :report-path]))))
          (log! (str "digest: complete (" (- (System/currentTimeMillis) t0) " ms)"))
          {:exit 0 :result @results})
        (catch Exception e
          (cu/print-error! (.getMessage e))
          {:exit 1})))))
