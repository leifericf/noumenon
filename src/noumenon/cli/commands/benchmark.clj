(ns noumenon.cli.commands.benchmark
  "CLI command handler for `benchmark`. Encapsulates the resume-checkpoint
   compatibility check and the full run-opts assembly."
  (:require [clojure.string :as str]
            [noumenon.benchmark :as bench]
            [noumenon.cli :as cli]
            [noumenon.cli.util :as cu]
            [noumenon.llm :as llm]
            [noumenon.util :refer [log!]]))

(defn- run-benchmark-impl!
  "Shared benchmark runner for fresh and resume paths."
  [db repo-path answer-llm opts]
  (try
    (bench/run-benchmark! db repo-path answer-llm
                          :meta-db (:meta-db opts)
                          :judge-llm (:judge-llm opts)
                          :model-config (:model-config opts)
                          :checkpoint-dir (:checkpoint-dir opts)
                          :resume-checkpoint (:resume-checkpoint opts)
                          :budget (:budget opts)
                          :mode (:mode opts)
                          :canary (:canary opts)
                          :concurrency (or (:concurrency opts) 3)
                          :min-delay-ms (or (:min-delay opts) 0)
                          :conn (:conn opts)
                          :report? (:report? opts)
                          :db-dir (:db-dir opts)
                          :db-name (:db-name opts))
    {:exit 0}
    (catch Exception e
      (cu/print-error! (.getMessage e))
      (log! (str "Resume with: " cli/program-name " benchmark " repo-path " --resume"))
      {:exit 2})))

(def ^:private compat-field-labels
  {:repo-path          "Repository path"
   :commit-sha         "Git HEAD commit"
   :question-set-hash  "Question set"
   :model-config       "Model configuration"
   :mode               "Run mode"
   :rubric-hash        "Rubric"
   :answer-prompt-hash "Answer prompt"})

(def ^:private hash-fields
  #{:question-set-hash :rubric-hash :answer-prompt-hash})

(defn- format-compat-error
  "Format a checkpoint compatibility error message."
  [mismatches]
  (str "Incompatible checkpoint. The benchmark configuration has changed "
       "since this checkpoint was created.\n"
       "Mismatched fields:\n"
       (str/join "\n"
                 (map (fn [{:keys [field checkpoint current]}]
                        (let [label (get compat-field-labels field (name field))]
                          (if (hash-fields field)
                            (str "  " label ": (changed)")
                            (str "  " label ": checkpoint=" checkpoint
                                 " current=" current))))
                      mismatches))
       "\nStart a fresh run: " cli/program-name " benchmark <repo-path>"))

(defn- do-benchmark-resume
  "Handle --resume path for benchmark."
  [checkpoint-dir resume db repo-path answer-llm run-opts]
  (let [cp-path (bench/find-checkpoint checkpoint-dir resume)
        cp      (when cp-path
                  (try (bench/checkpoint-read cp-path)
                       (catch Exception e
                         (cu/print-error! (str "Failed to parse checkpoint: " (.getMessage e)))
                         nil)))
        compat  (when cp
                  (let [questions (bench/load-questions)
                        rubric    (bench/load-rubric)]
                    (bench/validate-resume-compatibility
                     cp {:repo-path          (str repo-path)
                         :commit-sha         (bench/repo-head-sha repo-path)
                         :question-set-hash  (bench/question-set-hash questions)
                         :model-config       (:model-config run-opts)
                         :mode               (:mode run-opts)
                         :rubric-hash        (bench/question-set-hash (:judge-template rubric))
                         :answer-prompt-hash (bench/question-set-hash
                                              (bench/answer-prompt "{{q}}" "{{ctx}}"))})))]
    (cond
      (not cp-path)
      (do (cu/print-error! (if (= "latest" resume)
                             "No checkpoint files found"
                             (str "Checkpoint not found: " resume)))
          {:exit 1})

      (not cp)
      {:exit 1}

      (not (:ok compat))
      (do (cu/print-error! (format-compat-error (:mismatches compat)))
          {:exit 1})

      :else
      (run-benchmark-impl! db repo-path answer-llm
                           (assoc run-opts :resume-checkpoint cp)))))

(defn- build-benchmark-opts
  "Build the run-opts map for benchmark from CLI flags."
  [{:keys [max-questions stop-after max-cost model judge-model provider
           concurrency min-delay skip-raw skip-judge deterministic-only
           canary layers report]}
   conn meta-db]
  {:meta-db        meta-db
   :judge-llm      (:prompt-fn (llm/wrap-as-prompt-fn-from-opts
                                {:provider provider
                                 :model    (or judge-model model)}))
   :model-config   {:model model :judge-model (or judge-model model)
                    :provider provider}
   :checkpoint-dir "data/benchmarks/runs"
   :budget         {:max-questions max-questions
                    :stop-after-ms (when stop-after (* stop-after 1000))
                    :max-cost-usd  max-cost}
   :mode           (cond-> {}
                     layers             (assoc :layers layers)
                     skip-raw           (assoc :skip-raw true)
                     skip-judge         (assoc :skip-judge true)
                     deterministic-only (assoc :deterministic-only true))
   :canary         canary
   :concurrency    concurrency
   :min-delay      min-delay
   :conn           conn
   :report?        report})

(defn do-benchmark
  "Run the benchmark subcommand."
  [{:keys [resume model provider] :as opts}]
  (cu/with-valid-repo
    opts
    (fn [ctx]
      (try
        (cu/with-existing-db
          ctx
          (fn [{:keys [conn db meta-db]}]
            (let [answer-llm (:prompt-fn (llm/wrap-as-prompt-fn-from-opts
                                          {:provider provider :model model}))
                  run-opts   (assoc (build-benchmark-opts opts conn meta-db)
                                    :db-dir (:db-dir ctx) :db-name (:db-name ctx))]
              (if resume
                (do-benchmark-resume "data/benchmarks/runs" resume db
                                     (:repo-path opts) answer-llm run-opts)
                (run-benchmark-impl! db (:repo-path opts) answer-llm run-opts)))))
        (catch clojure.lang.ExceptionInfo e
          (cu/print-error! (.getMessage e))
          {:exit 1})))))
