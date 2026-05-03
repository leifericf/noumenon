(ns noumenon.mcp.handlers.benchmark
  "MCP tool handlers for the benchmark cluster."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.benchmark :as bench]
            [noumenon.llm :as llm]
            [noumenon.mcp.util :as mu]
            [noumenon.util :as util]))

(defn- find-run
  "Find a benchmark run by ID, or the latest run if no ID given."
  [db run-id]
  (let [runs (if run-id
               (d/q '[:find (pull ?r [*]) :in $ ?id
                      :where [?r :bench.run/id ?id]]
                    db run-id)
               (d/q '[:find (pull ?r [*])
                      :where [?r :bench.run/id _]]
                    db))]
    (->> runs (map first) (sort-by :bench.run/started-at) last)))

(defn- format-run-summary
  "Format a benchmark run as a human-readable summary string."
  [run detail?]
  (let [base (str "Run: " (:bench.run/id run)
                  "\nStatus: " (name (:bench.run/status run))
                  "\nCommit: " (:bench.run/commit-sha run)
                  "\nQuestions: " (:bench.run/question-count run)
                  (when-let [fm (:bench.run/full-mean run)]
                    (str "\nFull mean: " (format "%.1f%%" (* 100.0 (double fm)))))
                  (when-let [rm (:bench.run/raw-mean run)]
                    (str "\nRaw mean: " (format "%.1f%%" (* 100.0 (double rm)))))
                  (when (:bench.run/canonical? run) "\nCanonical: true"))]
    (if-not detail?
      base
      (str base "\n\nPer-question results:\n"
           (str/join "\n"
                     (map (fn [r]
                            (str (name (:bench.result/question-id r))
                                 " " (name (or (:bench.result/category r) :unknown))
                                 " full=" (name (or (:bench.result/full-score r) :n/a))
                                 " raw=" (name (or (:bench.result/raw-score r) :n/a))))
                          (sort-by :bench.result/question-id
                                   (:bench.run/results run))))))))

(defn handle-benchmark-run [args defaults]
  (mu/validate-llm-inputs! args)
  (mu/with-conn args defaults
    (fn [{:keys [conn meta-db db db-dir db-name repo-path]}]
      (let [{:keys [prompt-fn]}
            (llm/wrap-as-prompt-fn-from-opts {:provider (or (args "provider") (:provider defaults))
                                              :model    (or (args "model") (:model defaults))})
            layers      (mu/validate-layers (args "layers"))
            mode        (cond-> {}
                          layers (assoc :layers layers))
            model-cfg   {:provider (or (args "provider") (:provider defaults))
                         :model    (or (args "model") (:model defaults))}
            result      (bench/run-benchmark! db repo-path prompt-fn
                                              :meta-db meta-db
                                              :conn conn
                                              :mode mode
                                              :model-config model-cfg
                                              :budget {:max-questions (args "max_questions")}
                                              :report? (args "report")
                                              :progress-fn (:progress-fn defaults)
                                              :concurrency 3
                                              :db-dir db-dir :db-name db-name)]
        (mu/tool-result
         (str "Benchmark complete. Run ID: " (:run-id result)
              "\nQuestions: " (get-in result [:aggregate :question-count])
              (when-let [fm (get-in result [:aggregate :full-mean])]
                (str "\nFull mean: " (format "%.1f%%" (* 100.0 (double fm)))))
              (when-let [rm (get-in result [:aggregate :raw-mean])]
                (str "\nRaw mean: " (format "%.1f%%" (* 100.0 (double rm)))))
              (when-let [rp (:report-path result)]
                (str "\nReport: " rp))))))))

(defn handle-benchmark-results [args defaults]
  (when-let [rid (args "run_id")]
    (util/validate-string-length! "run_id" rid mu/max-run-id-len))
  (mu/with-conn args defaults
    (fn [{:keys [db]}]
      (if-let [run (find-run db (args "run_id"))]
        (mu/tool-result (format-run-summary run (args "detail")))
        (mu/tool-error "No benchmark runs found.")))))

(defn handle-benchmark-compare [args defaults]
  (util/validate-string-length! "run_id_a" (args "run_id_a") mu/max-run-id-len)
  (util/validate-string-length! "run_id_b" (args "run_id_b") mu/max-run-id-len)
  (mu/with-conn args defaults
    (fn [{:keys [db]}]
      (let [id-a (args "run_id_a")
            id-b (args "run_id_b")
            pull-run (fn [id]
                       (ffirst (d/q '[:find (pull ?r [*]) :in $ ?id
                                      :where [?r :bench.run/id ?id]]
                                    db id)))
            run-a (pull-run id-a)
            run-b (pull-run id-b)]
        (cond
          (not run-a) (mu/tool-error (str "Run not found: " id-a))
          (not run-b) (mu/tool-error (str "Run not found: " id-b))
          :else
          (let [{:keys [deltas]} (bench/compare-runs run-a run-b)]
            (mu/tool-result
             (str "Comparing " id-a " vs " id-b "\n\n"
                  (str/join "\n"
                            (map (fn [[k v]]
                                   (str (name k) ": "
                                        (if (pos? v) "+" "")
                                        (format "%.1f" (* 100.0 v)) "pp"))
                                 (sort-by key deltas)))))))))))
