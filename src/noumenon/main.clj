(ns noumenon.main
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.benchmark :as bench]
            [noumenon.cli :as cli]
            [noumenon.db :as db]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.agent :as agent]
            [noumenon.llm :as llm]
            [noumenon.longbench :as longbench]
            [noumenon.query :as query]))

;; --- Helpers ---

(defn derive-db-name
  "Extract database name from a repo path: last path component, trailing slashes stripped."
  [repo-path]
  (-> repo-path str (str/replace #"/+$" "") (str/split #"/") last))

(defn resolve-db-dir
  "Resolve the database storage directory. Defaults to data/datomic/ relative to cwd."
  [opts]
  (or (:db-dir opts)
      (str (.getAbsolutePath (io/file "data" "datomic")))))

(def ^:private default-provider-by-command
  {"analyze" "claude"
   "agent" llm/default-provider
   "benchmark" llm/default-provider
   "longbench" llm/default-provider})

(def ^:private default-model-by-command
  {"agent" llm/default-model-alias
   "longbench" llm/default-model-alias})

(defn- parse-args [args]
  (cli/parse-args args))

(def ^:private usage-text
  (str/join "\n"
            ["Usage: clj -M:run <subcommand> [options] <repo-path>"
             ""
             "Subcommands:"
             "  import     Import git history and file structure into Datomic"
             "  analyze    Enrich imported files with LLM-driven semantic analysis"
             "  query      Run a named Datalog query against the knowledge graph"
             "  status     Show import counts for a repository"
             "  agent      Ask a question about a repository using AI-powered querying"
             "  benchmark  Run benchmark suite against a repository"
             "  longbench  Run LongBench v2 standard benchmark"
             ""
             "Options:"
             "  --db-dir <dir>        Override default storage directory (default: data/datomic/)"
             "  --model <alias>       Model alias (e.g. sonnet, haiku)"
             "  --provider <name>     Provider: glm (default), claude, claude-api, or claude-cli"
             ""
             "Agent options:"
             "  --max-iterations <n>  Max query iterations (default: 10)"
             "  --max-cost <dollars>  Stop when session cost exceeds threshold"
             "  -v                    Verbose: log iterations to stderr"
             ""
             "Benchmark options:"
             "  --skip-raw            Omit raw-context condition (halves LLM calls)"
             "  --skip-judge          Skip LLM judge stages (deterministic scores only)"
             "  --fast                Sugar for --skip-raw --skip-judge"
             "  --canary              Run q01+q02 first as canary; warn if both fail"
             "  --resume [run-id]     Resume from checkpoint (default: latest)"
             "  --max-questions <n>   Stop after n questions"
             "  --stop-after <secs>   Stop after n seconds"
             "  --max-cost <dollars>  Stop when session cost exceeds threshold"
             "  --judge-model <alias> Model alias for judge stages"
             "  --concurrency <n>    Parallel pair workers, 1-20 (default: 4)"
             "  --min-delay <ms>     Min delay between LLM requests (default: 0)"
             ""
             "LongBench subcommands:"
             "  longbench download              Download LongBench v2 dataset"
             "  longbench run [options]          Run benchmark"
             "  longbench results [run-id]       Show results"
             ""
             "LongBench run options:"
             "  --resume [run-id]     Resume from checkpoint (default: latest)"
             "  --max-questions <n>   Stop after n questions"
             "  --stop-after <secs>   Stop after n seconds"
             "  --max-cost <dollars>  Stop when session cost exceeds threshold"
             "  --model <alias>       Model alias (e.g. sonnet, haiku, opus)"
             "  --provider <name>     Provider: glm (default), claude, claude-api, or claude-cli"
             "  --concurrency <n>    Parallel workers, 1-20 (default: 4)"
             "  --min-delay <ms>     Min delay between LLM requests (default: 0)"
             ""
             "LongBench results options:"
             "  --detail              Show per-question detail table"]))

(defn- print-usage! []
  (binding [*out* *err*]
    (println usage-text)))

(defn- print-error! [msg]
  (binding [*out* *err*]
    (println (str "Error: " msg))))

;; --- Subcommands ---

(defn- validate-repo-path [repo-path]
  (let [f (io/file repo-path)]
    (cond
      (not (.exists f))
      (str "Path does not exist: " repo-path)

      (not (.isDirectory f))
      (str "Path is not a directory: " repo-path)

      (not (.exists (io/file f ".git")))
      (str "Path is not a git repository: " repo-path))))

(defn- db-exists? [db-dir db-name]
  (.exists (io/file db-dir "noumenon" db-name)))

(defn- db-path
  [{:keys [db-dir db-name]}]
  (.getAbsolutePath (io/file db-dir "noumenon" db-name)))

(defn- build-context
  [{:keys [repo-path] :as opts}]
  {:repo-path repo-path
   :db-dir    (resolve-db-dir opts)
   :db-name   (derive-db-name repo-path)})

(defn- missing-db-msg
  [{:keys [db-name]}]
  (str "No database found for \"" db-name "\". Run `import` first."))

(defn- with-valid-repo
  [opts run!]
  (if-let [err (validate-repo-path (:repo-path opts))]
    (do (print-error! err) {:exit 1})
    (run! (build-context opts))))

(defn- with-existing-db
  [ctx run!]
  (if-not (db-exists? (:db-dir ctx) (:db-name ctx))
    (do (print-error! (missing-db-msg ctx)) {:exit 1})
    (let [conn (db/connect-and-ensure-schema (:db-dir ctx) (:db-name ctx))]
      (run! (assoc ctx :conn conn :db (d/db conn))))))

(defn do-import
  "Run the import subcommand. Returns {:exit n :result map-or-nil}."
  [opts]
  (with-valid-repo
    opts
    (fn [{:keys [repo-path db-dir db-name] :as ctx}]
      (let [conn    (db/connect-and-ensure-schema db-dir db-name)
            git-r   (git/import-commits! conn repo-path)
            files-r (files/import-files! conn repo-path)]
        {:exit   0
         :result (merge (select-keys git-r [:commits-imported :commits-skipped])
                        (select-keys files-r [:files-imported :files-skipped :dirs-imported])
                        {:db-path (db-path ctx)})}))))

(defn do-analyze
  "Run the analyze subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path model provider] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [conn]}]
            (let [provider-kw (llm/provider->kw
                               (or provider (default-provider-by-command "analyze")))
                  invoke-llm  (llm/make-prompt-fn
                               (llm/make-invoke-fn provider-kw {:model model}))]
              {:exit   0
               :result (analyze/analyze-repo! conn repo-path invoke-llm)})))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          {:exit 1})))))

(defn do-query
  "Run the query subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [query-name] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (with-existing-db
        ctx
        (fn [{:keys [db]}]
          (let [{:keys [ok error]} (query/run-named-query db query-name)]
            (if error
              (do (print-error! error) {:exit 1})
              {:exit 0 :result ok})))))))

(defn do-agent
  "Run the agent subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [question model provider max-iterations verbose] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [db db-name]}]
            (let [provider-kw (llm/provider->kw
                               (or provider (default-provider-by-command "agent")))
                  model-id    (llm/model-alias->id
                               (or model (default-model-by-command "agent")))
                  invoke-fn   (llm/make-invoke-fn provider-kw
                                                  {:model       model-id
                                                   :temperature 0.3
                                                   :max-tokens  4096})
                  result      (agent/ask db question
                                         (cond-> {:invoke-fn invoke-fn :repo-name db-name}
                                           max-iterations (assoc :max-iterations max-iterations)))]
              (when verbose
                (binding [*out* *err*]
                  (doseq [step (:steps result)]
                    (println (str "agent/step iteration=" (:iteration step)
                                  (when (:tool-result step)
                                    (str " tool-result-size=" (count (:tool-result step))))
                                  (when (:error step) (str " error=" (:error step)))
                                  (when (:answer step) " answer=yes"))))))
              {:exit   0
               :result {:answer (:answer result)
                        :status (:status result)
                        :usage  (:usage result)}})))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          {:exit 1})))))

(defn do-status
  "Run the status subcommand. Returns {:exit n :result map-or-nil}."
  [opts]
  (with-valid-repo
    opts
    (fn [ctx]
      (with-existing-db
        ctx
        (fn [{:keys [db] :as c}]
          {:exit   0
           :result {:commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
                    :files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
                    :dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))
                    :db-path (db-path c)}})))))

;; --- Benchmark ---

(defn- run-benchmark-impl!
  "Shared benchmark runner for fresh and resume paths."
  [db repo-path answer-llm opts]
  (try
    (bench/run-benchmark! db repo-path answer-llm
                          :judge-llm (:judge-llm opts)
                          :model-config (:model-config opts)
                          :checkpoint-dir (:checkpoint-dir opts)
                          :resume-checkpoint (:resume-checkpoint opts)
                          :budget (:budget opts)
                          :mode (:mode opts)
                          :canary (:canary opts)
                          :concurrency (or (:concurrency opts) 4)
                          :min-delay-ms (or (:min-delay opts) 0))
    {:exit 0}
    (catch Exception e
      (binding [*out* *err*]
        (println (str "bench/error " (.getMessage e)))
        (println (str "Resume with: clj -M:run benchmark " repo-path " --resume")))
      {:exit 2})))

(defn- do-benchmark-resume
  "Handle --resume path for benchmark. Returns {:exit n}."
  [checkpoint-dir resume db repo-path answer-llm run-opts]
  (let [cp-path (bench/find-checkpoint checkpoint-dir resume)
        cp      (when cp-path
                  (try (bench/checkpoint-read cp-path)
                       (catch Exception e
                         (print-error! (str "Failed to parse checkpoint: " (.getMessage e)))
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
      (do (print-error! (if (= "latest" resume)
                          "No checkpoint files found"
                          (str "Checkpoint not found: " resume)))
          {:exit 1})

      (not cp)
      {:exit 1}

      (not (:ok compat))
      (do (print-error!
           (str "Incompatible checkpoint. Mismatched fields:\n"
                (str/join "\n"
                          (map #(str "  " (name (:field %))
                                     ": checkpoint=" (:checkpoint %)
                                     " current=" (:current %))
                               (:mismatches compat)))))
          {:exit 1})

      :else
      (run-benchmark-impl! db repo-path answer-llm
                           (assoc run-opts :resume-checkpoint cp)))))

(defn do-benchmark
  "Run the benchmark subcommand. Returns {:exit n}."
  [{:keys [repo-path resume max-questions stop-after max-cost model judge-model provider
           concurrency min-delay skip-raw skip-judge canary] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (try
      (let [db-dir         (resolve-db-dir opts)
            db-name        (derive-db-name repo-path)
            checkpoint-dir "data/benchmarks/runs"
            provider       (or provider (default-provider-by-command "benchmark"))
            provider-kw    (llm/provider->kw provider)
            answer-llm     (llm/make-prompt-fn
                            (llm/make-invoke-fn provider-kw {:model model}))
            judge-llm      (llm/make-prompt-fn
                            (llm/make-invoke-fn provider-kw {:model (or judge-model model)}))
            run-opts       {:judge-llm      judge-llm
                            :model-config   {:model model :judge-model (or judge-model model)
                                             :provider provider}
                            :checkpoint-dir checkpoint-dir
                            :budget         {:max-questions max-questions
                                             :stop-after-ms (when stop-after (* stop-after 1000))
                                             :max-cost-usd  max-cost}
                            :mode           (cond-> {}
                                              skip-raw   (assoc :skip-raw true)
                                              skip-judge (assoc :skip-judge true))
                            :canary         canary
                            :concurrency    concurrency
                            :min-delay      min-delay}]
        (if-not (db-exists? db-dir db-name)
          (do (print-error! (str "No database found for \"" db-name "\". Run `import` first."))
              {:exit 1})
          (let [conn (db/connect-and-ensure-schema db-dir db-name)
                db   (d/db conn)]
            (if resume
              (do-benchmark-resume checkpoint-dir resume db repo-path answer-llm run-opts)
              (run-benchmark-impl! db repo-path answer-llm run-opts)))))
      (catch clojure.lang.ExceptionInfo e
        (print-error! (.getMessage e))
        {:exit 1}))))

;; --- LongBench ---

(defn- do-longbench-run
  [{:keys [resume max-questions stop-after max-cost model provider concurrency min-delay]}]
  (try
    (let [provider-kw    (llm/provider->kw (or provider (default-provider-by-command "longbench")))
          model-id       (llm/model-alias->id (or model (default-model-by-command "longbench")))
          checkpoint-dir "data/longbench/runs"
          invoke-llm     (llm/make-invoke-fn provider-kw
                                             {:model       model-id
                                              :temperature 0.1
                                              :max-tokens  128})
          budget         {:max-questions max-questions
                          :stop-after-ms (when stop-after (* stop-after 1000))
                          :max-cost-usd  max-cost}]
      (when (= :claude-cli provider-kw)
        (binding [*out* *err*]
          (println "longbench/param-deviation provider=claude-cli temperature=default max_tokens=default")))
      (if resume
        (let [cp-path (bench/find-checkpoint checkpoint-dir
                                             (if (string? resume) resume "latest"))]
          (if-not cp-path
            (do (print-error! (if (= "latest" resume)
                                "No checkpoint files found"
                                (str "Checkpoint not found: " resume)))
                {:exit 1})
            (let [cp (bench/checkpoint-read cp-path)]
              (longbench/run-longbench-resume! invoke-llm cp
                                               :checkpoint-dir checkpoint-dir
                                               :budget budget
                                               :concurrency (or concurrency 4)
                                               :min-delay-ms (or min-delay 0))
              {:exit 0})))
        (do
          (longbench/run-longbench! invoke-llm
                                    :checkpoint-dir checkpoint-dir
                                    :budget budget
                                    :concurrency (or concurrency 4)
                                    :min-delay-ms (or min-delay 0))
          {:exit 0})))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (#{429 500 502 503} (:status data))
          (do (binding [*out* *err*]
                (println (str "longbench/api-error " (.getMessage e))))
              {:exit 2})
          (do (print-error! (.getMessage e))
              {:exit 1}))))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "longbench/error " (.getMessage e)))
        (println "Resume with: clj -M:run longbench run --resume"))
      {:exit 2})))

(defn- do-longbench-results
  [{:keys [run-id detail]}]
  (let [rid      (or run-id
                     (when-let [cp (longbench/find-latest-run)]
                       (-> (java.io.File. ^String cp) .getName
                           (str/replace #"\.edn$" ""))))
        _        (when-not rid
                   (print-error! "No runs found.")
                   (throw (ex-info "No runs found" {})))
        results  (longbench/load-results rid)]
    (if-not results
      (do (print-error! (str "No results found for run: " rid))
          {:exit 1})
      (let [agg (longbench/aggregate-results
                 (mapv (fn [r] {:prediction (:pred r) :answer (:answer r)
                                :difficulty (:difficulty r) :length (:length r)})
                       results))
            fmt (fn [v] (if v (format "%5.1f" (double v)) "  n/a"))]
        (println (str "Run: " rid))
        (println (str "Questions: " (:total agg)))
        (println)
        (println "  Overall  Easy   Hard   Short  Med    Long")
        (println (str "  " (fmt (:overall agg))
                      "  " (fmt (:easy agg))
                      "  " (fmt (:hard agg))
                      "  " (fmt (:short agg))
                      "  " (fmt (:medium agg))
                      "  " (fmt (:long agg))))
        (when detail
          (println)
          (println "  ID                 Pred  Truth  Correct?")
          (println "  ---                ----  -----  --------")
          (doseq [r results]
            (println (format "  %-20s %-5s %-5s  %s"
                             (:_id r)
                             (or (:pred r) "nil")
                             (:answer r)
                             (if (:judge r) "yes" "no")))))
        {:exit 0}))))

(defn do-longbench
  "Run the longbench subcommand. Returns {:exit n}."
  [{:keys [longbench-command] :as opts}]
  (case longbench-command
    "download" (try
                 (longbench/download-dataset!)
                 {:exit 0}
                 (catch Exception e
                   (print-error! (.getMessage e))
                   {:exit 1}))
    "run"      (do-longbench-run opts)
    "results"  (try
                 (do-longbench-results opts)
                 (catch clojure.lang.ExceptionInfo e
                   (print-error! (.getMessage e))
                   {:exit 1}))))

;; --- Error dispatch ---

(defn- allowed-provider-help
  [{:keys [subcommand]}]
  (case subcommand
    "benchmark" "'glm', 'claude', 'claude-api', or 'claude-cli'"
    "longbench" "'glm', 'claude', 'claude-api', or 'claude-cli'"
    "agent" "'glm', 'claude', 'claude-api', or 'claude-cli'"
    "'glm', 'claude', 'claude-api', or 'claude-cli'"))

(def ^:private error-messages
  {:no-args                      nil
   :unknown-subcommand           #(str "Unknown subcommand: " (:subcommand %))
   :no-repo-path                 "Missing <repo-path> argument."
   :query-missing-args           "Usage: query <query-name> <repo-path>"
   :missing-db-dir-value         "Missing value for --db-dir."
   :unknown-flag                 #(str "Unknown option: " (:flag %))
   :invalid-max-questions        #(str "Invalid --max-questions value: " (:value %))
   :missing-max-questions-value  "Missing value for --max-questions."
   :invalid-stop-after           #(str "Invalid --stop-after value: " (:value %))
   :missing-stop-after-value     "Missing value for --stop-after."
   :missing-model-value          "Missing value for --model."
   :missing-judge-model-value    "Missing value for --judge-model."
   :missing-provider-value       "Missing value for --provider."
   :invalid-provider             #(str "Invalid --provider value: " (:value %)
                                       ". Must be " (allowed-provider-help %) ".")
   :invalid-max-cost             #(str "Invalid --max-cost value: " (:value %))
   :missing-max-cost-value       "Missing value for --max-cost."
   :invalid-concurrency          #(str "Invalid --concurrency value: " (:value %) ". Must be 1-20.")
   :missing-concurrency-value    "Missing value for --concurrency."
   :invalid-min-delay            #(str "Invalid --min-delay value: " (:value %) ". Must be >= 0.")
   :missing-min-delay-value      "Missing value for --min-delay."
   :longbench-no-subcommand      "Missing longbench subcommand. Usage: longbench <download|run|results>"
   :longbench-unknown-subcommand #(str "Unknown longbench subcommand: " (:longbench-command %)
                                       ". Usage: longbench <download|run|results>")
   :agent-missing-args           "Usage: agent <question> <repo-path> [options]"
   :invalid-max-iterations       #(str "Invalid --max-iterations value: " (:value %))
   :missing-max-iterations-value "Missing value for --max-iterations."})

(def ^:private errors-with-usage
  #{:no-args :unknown-subcommand :no-repo-path :missing-db-dir-value :unknown-flag})

;; --- Entry point ---

(defn run
  "Main dispatch. Returns {:exit n :result map-or-nil}."
  [args]
  (let [parsed (parse-args args)]
    (if-let [err (:error parsed)]
      (do (when-let [msg-or-fn (error-messages err)]
            (let [msg (if (fn? msg-or-fn) (msg-or-fn parsed) msg-or-fn)]
              (print-error! msg)))
          (when (errors-with-usage err)
            (print-usage!))
          {:exit 1})
      (let [result (case (:subcommand parsed)
                     "import"    (do-import parsed)
                     "analyze"   (do-analyze parsed)
                     "query"     (do-query parsed)
                     "agent"     (do-agent parsed)
                     "status"    (do-status parsed)
                     "benchmark" (do-benchmark parsed)
                     "longbench" (do-longbench parsed))]
        (when (and (:result result)
                   (not (#{"benchmark" "longbench"} (:subcommand parsed))))
          (prn (:result result)))
        result))))

(defn -main [& args]
  (let [{:keys [exit]} (run args)]
    (System/exit exit)))
