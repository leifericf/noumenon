(ns noumenon.main
  (:gen-class)
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
            [noumenon.imports :as imports]
            [noumenon.introspect :as introspect]
            [noumenon.mcp :as mcp]
            [noumenon.query :as query]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]]))

;; --- Helpers ---

(defn- print-usage! []
  (log! (cli/format-global-help)))

(defn- print-error! [msg]
  (log! (str "Error: " msg)))

;; --- Subcommands ---

(defn- validate-repo-path [repo-path]
  (when-let [reason (util/validate-repo-path repo-path)]
    (str "Path " reason ": " repo-path)))

(defn- db-exists? [db-dir db-name]
  (.exists (io/file db-dir "noumenon" db-name)))

(defn- db-path
  [{:keys [db-dir db-name]}]
  (.getAbsolutePath (io/file db-dir "noumenon" db-name)))

(defn- build-context
  [{:keys [repo-path] :as opts}]
  (let [db-name (util/derive-db-name repo-path)]
    (when-not db-name
      (throw (ex-info (str "Cannot derive database name from path: " repo-path)
                      {:repo-path repo-path})))
    {:repo-path repo-path
     :db-dir    (util/resolve-db-dir opts)
     :db-name   db-name}))

(defn- missing-db-msg
  [{:keys [db-name repo-path]}]
  (str "No database found for \"" db-name "\". Run: "
       cli/program-name " import " (or repo-path "<repo-path>")))

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

(defn- resolve-repo-path
  "If repo-path is a Git URL, clone to data/repos/<name>/ and return local path.
   If already cloned, reuse existing directory. Otherwise return repo-path as-is."
  [repo-path]
  (if-not (git/git-url? repo-path)
    repo-path
    (let [name   (git/url->repo-name repo-path)
          target (str "data/repos/" name)]
      (if (.isDirectory (io/file target ".git"))
        (do (log! (str "Using existing clone at " target)) target)
        (do (log! (str "Cloning " repo-path " into " target " ..."))
            (git/clone! repo-path target)
            target)))))

(defn do-import
  "Run the import subcommand. Returns {:exit n :result map-or-nil}."
  [opts]
  (with-valid-repo
    (update opts :repo-path resolve-repo-path)
    (fn [{:keys [repo-path db-dir db-name] :as ctx}]
      (let [conn     (db/connect-and-ensure-schema db-dir db-name)
            repo-uri (.getCanonicalPath (java.io.File. (str repo-path)))
            git-r    (git/import-commits! conn repo-path repo-uri)
            files-r  (files/import-files! conn repo-path repo-uri)
            head-sha (git/head-sha repo-path)]
        (when head-sha
          (d/transact conn {:tx-data [{:repo/uri repo-uri :repo/head-sha head-sha}]}))
        (log! (str "Next: run '" cli/program-name " enrich " repo-path
                   "' to build the import graph, then '"
                   cli/program-name " analyze " repo-path
                   "' for semantic metadata."))
        {:exit   0
         :result (merge (select-keys git-r [:commits-imported :commits-skipped])
                        (select-keys files-r [:files-imported :files-skipped :dirs-imported])
                        {:db-path    (db-path ctx)
                         :next-step  (str cli/program-name " enrich " repo-path)})}))))

(def ^:private valid-reanalyze-scopes
  #{"all" "prompt-changed" "model-changed" "stale"})

(defn- prepare-reanalysis!
  "Retract analysis attrs for files matching the reanalyze scope.
   Returns count of files marked for re-analysis, or nil if no scope given."
  [conn db reanalyze {:keys [prompt-hash model-id]}]
  (when reanalyze
    (let [scope (keyword reanalyze)
          files (analyze/files-for-reanalysis db scope {:prompt-hash prompt-hash
                                                        :model-id    model-id})
          paths (mapv :file/path files)
          n     (if (seq paths) (sync/retract-analysis! conn paths) 0)]
      (log! (str "Marked " n " file(s) for re-analysis (scope: " reanalyze ")"))
      n)))

(defn do-analyze
  "Run the analyze subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path model provider concurrency min-delay max-files reanalyze] :as opts}]
  (when (and reanalyze (not (valid-reanalyze-scopes reanalyze)))
    (print-error! (str "Invalid --reanalyze scope: " reanalyze
                       ". Must be one of: all, prompt-changed, model-changed, stale"))
    (System/exit 1))
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [conn]}]
            (let [{:keys [prompt-fn model-id]}
                  (llm/wrap-as-prompt-fn-from-opts {:provider provider :model model})
                  prompt-hash (analyze/prompt-hash (:template (analyze/load-prompt-template)))]
              (prepare-reanalysis! conn (d/db conn) reanalyze
                                   {:prompt-hash prompt-hash :model-id model-id})
              (let [result (analyze/analyze-repo! conn repo-path prompt-fn
                                                  (cond-> {:model-id     model-id
                                                           :concurrency  (or concurrency 3)
                                                           :min-delay-ms (or min-delay 0)}
                                                    max-files (assoc :max-files max-files)))]
                (log! (str "Next: run '" cli/program-name " query <query-name> " repo-path
                           "' or '" cli/program-name " ask -q \"...\" " repo-path
                           "' to explore the knowledge graph."))
                {:exit 0 :result result}))))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          (when-let [help (cli/format-subcommand-help "analyze")]
            (log!)
            (log! help))
          {:exit 1})))))

(defn do-enrich
  "Run the enrich subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [concurrency] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (with-existing-db
        ctx
        (fn [{:keys [conn repo-path]}]
          (let [result (imports/enrich-repo! conn repo-path
                                             {:concurrency (or concurrency 8)})]
            (log! (str "Next: run '" cli/program-name " analyze " repo-path
                       "' for semantic metadata, then '" cli/program-name
                       " query file-imports " repo-path "' to explore."))
            {:exit 0 :result result}))))))

(defn- build-sync-opts
  [{:keys [analyze model provider concurrency]}]
  (if analyze
    (let [{:keys [prompt-fn model-id]}
          (llm/wrap-as-prompt-fn-from-opts {:provider provider :model model})]
      {:concurrency         (or concurrency 8)
       :analyze-concurrency (or concurrency 3)
       :analyze?            true
       :model-id            model-id
       :invoke-llm          prompt-fn})
    {:concurrency         (or concurrency 8)
     :analyze-concurrency (or concurrency 3)}))

(defn do-update
  "Run the update subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [analyze] :as opts}]
  (with-valid-repo
    (update opts :repo-path resolve-repo-path)
    (fn [{:keys [repo-path db-dir db-name]}]
      (let [conn      (db/connect-and-ensure-schema db-dir db-name)
            repo-uri  (.getCanonicalPath (java.io.File. (str repo-path)))
            result    (sync/update-repo! conn repo-path repo-uri (build-sync-opts opts))]
        (when-not analyze
          (log! (str "Next: run '" cli/program-name " analyze " repo-path
                     "' to enrich with semantic metadata.")))
        {:exit 0 :result result}))))

(defn do-watch
  "Run the watch subcommand. Polls git HEAD and syncs on changes."
  [{:keys [interval] :as opts}]
  (with-valid-repo
    opts
    (fn [{:keys [repo-path db-dir db-name]}]
      (let [conn       (db/connect-and-ensure-schema db-dir db-name)
            repo-uri   (.getCanonicalPath (java.io.File. (str repo-path)))
            interval-s (or interval 30)
            sync-opts  (build-sync-opts opts)]
        (log! (str "Watching " repo-path " (polling every " interval-s "s)"))
        (loop [failures 0
               last-had-changes? true]
          (let [result  (try
                          (sync/update-repo! conn repo-path repo-uri
                                             (assoc sync-opts :quiet? (not last-had-changes?)))
                          (catch Exception e
                            (log! (str "Update error: " (.getMessage e)))
                            ::error))
                failed?      (= result ::error)
                idle?        (and (not failed?) (= :up-to-date (:status result)))
                had-changes? (and (not failed?) (not idle?))
                new-failures (if failed? (inc failures) 0)]
            (when (and failed? (= new-failures 5))
              (log! (str "WARNING: 5 consecutive failures. Check database and repository.")))
            (Thread/sleep (* interval-s 1000 (if (>= new-failures 3) (min new-failures 10) 1)))
            (recur new-failures (or had-changes? failed?))))))))

(defn- do-query-list
  "List available named queries with descriptions."
  []
  (let [queries (->> (query/list-query-names)
                     (mapv (fn [qname]
                             {:name        qname
                              :description (or (:description (query/load-named-query qname)) "")})))
        width   (+ 4 (reduce max 0 (map (comp count :name) queries)))
        fmt-str (str "  %-" width "s %s")]
    (doseq [{:keys [name description]} queries]
      (log! (format fmt-str name description)))
    {:exit 0 :result queries}))

(defn do-query
  "Run the query subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [query-name list-queries params] :as opts}]
  (if list-queries
    (do-query-list)
    (with-valid-repo
      opts
      (fn [ctx]
        (with-existing-db
          ctx
          (fn [{:keys [db]}]
            (let [kw-params (into {} (map (fn [[k v]] [(keyword k) v])) params)
                  {:keys [ok error]} (query/run-named-query db query-name kw-params)]
              (if error
                (do (print-error! error)
                    (when (str/starts-with? (str error) "Missing required inputs")
                      (log! "Hint: use --param key=value to supply query inputs."))
                    {:exit 1})
                {:exit 0 :result ok}))))))))

(defn do-ask
  "Run the ask subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [question model provider max-iterations continue-from verbose] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [db db-name]}]
            (let [{:keys [invoke-fn]}
                  (llm/make-messages-fn-from-opts {:provider    provider
                                                   :model       model
                                                   :temperature 0.3
                                                   :max-tokens  4096})
                  result (agent/ask db question
                                    (cond-> {:invoke-fn invoke-fn :repo-name db-name}
                                      max-iterations (assoc :max-iterations max-iterations)
                                      continue-from  (assoc :continue-from continue-from)))]
              (when verbose
                (let [max-iters (or max-iterations 10)]
                  (doseq [step (:steps result)]
                    (let [i   (:iteration step)
                          tag (cond
                                (:answer step)      "answer"
                                (:error step)       (str "error: " (:error step))
                                (:tool-result step) (let [parsed (:parsed step)
                                                          tool   (some-> parsed :tool name)]
                                                      (str "tool: " (or tool "unknown")
                                                           " (" (count (:tool-result step))
                                                           " chars)"))
                                :else               "thinking")]
                      (log! (str "  [" i "/" max-iters "] " tag))))))
              (let [exhausted? (= :budget-exhausted (:status result))
                    answer     (:answer result)
                    session-id (:session-id result)]
                (cond
                  (and exhausted? (not answer))
                  (do (log! "Budget exhausted — no answer found.")
                      {:exit 2 :result {:status :budget-exhausted :usage (:usage result)}})

                  exhausted?
                  (do (log! answer)
                      (log! (str "\n[Session " session-id " saved — re-run with"
                                 " --continue-from " session-id " to resume]"))
                      {:exit   2
                       :result {:answer answer :status :budget-exhausted
                                :session-id session-id :usage (:usage result)}})

                  :else
                  (do (when answer (log! answer))
                      {:exit   0
                       :result {:answer answer :status (:status result)
                                :usage (:usage result)}}))))))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          {:exit 1})))))

(defn do-show-schema
  "Run the show-schema subcommand. Returns {:exit n :result map-or-nil}."
  [opts]
  (with-valid-repo
    opts
    (fn [ctx]
      (with-existing-db
        ctx
        (fn [{:keys [db]}]
          (let [summary (query/schema-summary db)]
            (log! summary)
            {:exit 0 :result summary}))))))

(defn do-status
  "Run the status subcommand. Returns {:exit n :result map-or-nil}."
  [opts]
  (with-valid-repo
    opts
    (fn [ctx]
      (with-existing-db
        ctx
        (fn [{:keys [db] :as c}]
          (let [stats (merge (query/repo-stats db)
                             {:db-path (db-path c)})
                head  (when-let [sha (:head-sha stats)]
                        (str " -- head: " (subs sha 0 (min 7 (count sha)))))]
            (log! (str (:commits stats) " commits, "
                       (:files stats) " files, "
                       (:dirs stats) " directories"
                       " -- db: " (:db-path stats)
                       head))
            {:exit 0 :result stats}))))))

;; --- List Databases ---

(defn- format-date [inst]
  (when inst
    (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") inst)))

(defn- format-pipeline
  "Format pipeline stages as [import:3 analyze:42 enrich:1]."
  [ops]
  (let [stages (keep (fn [op]
                       (when-let [n (ops op)]
                         (str (clojure.core/name op) ":" n)))
                     [:import :analyze :enrich])]
    (when (seq stages)
      (str "  [" (str/join " " stages) "]"))))

(defn- print-db-stats
  [{:keys [name commits files dirs latest cost ops error]}]
  (if error
    (log! (format "  %-24s (error: %s)" name error))
    (let [date-str  (if latest (str "  (latest: " (format-date latest) ")") "")
          cost-str  (if (pos? cost) (format "  $%.2f" cost) "")
          stage-str (or (format-pipeline ops) "")]
      (log! (format "  %-24s %d commits, %d files, %d dirs%s%s%s"
                    name commits files dirs date-str cost-str stage-str)))))

(defn do-list-databases
  "List all databases or delete one. Returns {:exit n :result vec-or-nil}."
  [opts]
  (let [db-dir (util/resolve-db-dir opts)]
    (if-let [db-name (:delete opts)]
      (let [client (db/create-client db-dir)]
        (if-not (db-exists? db-dir db-name)
          (do (print-error! (str "Database \"" db-name "\" not found.")) {:exit 1})
          (do (db/delete-db client db-name)
              (log! (str "Deleted database \"" db-name "\"."))
              (log! (str "Re-import: " cli/program-name " import <repo-path>"))
              {:exit 0})))
      (let [names (db/list-db-dirs db-dir)]
        (if (empty? names)
          (do (log! (str "No databases found in " db-dir)) {:exit 0 :result []})
          (let [client (db/create-client db-dir)
                stats  (mapv #(db/db-stats client %) names)]
            (doseq [s stats] (print-db-stats s))
            {:exit 0 :result stats}))))))

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
                          :concurrency (or (:concurrency opts) 3)
                          :min-delay-ms (or (:min-delay opts) 0)
                          :conn (:conn opts)
                          :report? (:report? opts))
    {:exit 0}
    (catch Exception e
      (print-error! (.getMessage e))
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

(defn- format-compat-error
  "Format a checkpoint compatibility error message."
  [mismatches]
  (str "Incompatible checkpoint. The benchmark configuration has changed "
       "since this checkpoint was created. Start a fresh run without --resume.\n"
       "Mismatched fields:\n"
       (str/join "\n"
                 (map #(str "  " (get compat-field-labels (:field %) (name (:field %)))
                            ": checkpoint=" (:checkpoint %)
                            " current=" (:current %))
                      mismatches))))

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
      (do (print-error! (format-compat-error (:mismatches compat)))
          {:exit 1})

      :else
      (run-benchmark-impl! db repo-path answer-llm
                           (assoc run-opts :resume-checkpoint cp)))))

(defn- build-benchmark-opts
  "Build the run-opts map for benchmark from CLI flags."
  [{:keys [max-questions stop-after max-cost model judge-model provider
           concurrency min-delay skip-raw skip-judge deterministic-only
           canary layers report]}
   conn]
  {:judge-llm      (:prompt-fn (llm/wrap-as-prompt-fn-from-opts
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
  "Run the benchmark subcommand. Returns {:exit n}."
  [{:keys [resume model provider] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [conn db]}]
            (let [answer-llm (:prompt-fn (llm/wrap-as-prompt-fn-from-opts
                                          {:provider provider :model model}))
                  run-opts   (build-benchmark-opts opts conn)]
              (if resume
                (do-benchmark-resume "data/benchmarks/runs" resume db
                                     (:repo-path opts) answer-llm run-opts)
                (run-benchmark-impl! db (:repo-path opts) answer-llm run-opts)))))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          {:exit 1})))))

(defn- run-digest-step!
  "Run one digest step with timing. Stores result in results atom under key."
  [results step-key label run-fn]
  (log! (str "digest: " label "..."))
  (let [start (System/currentTimeMillis)
        r     (run-fn)]
    (log! (str "digest: " label " done (" (- (System/currentTimeMillis) start) " ms)"))
    (swap! results assoc step-key r)))

(defn do-digest
  "Run the full pipeline: import → enrich → analyze → benchmark.
   Each step is idempotent and can be skipped with --skip-* flags."
  [{:keys [skip-import skip-enrich skip-analyze skip-benchmark
           model provider concurrency max-questions layers report] :as opts}]
  (with-valid-repo
    (update opts :repo-path resolve-repo-path)
    (fn [{:keys [repo-path db-dir db-name]}]
      (try
        (let [conn      (db/connect-and-ensure-schema db-dir db-name)
              repo-uri  (.getCanonicalPath (java.io.File. (str repo-path)))
              needs-llm (not (and skip-analyze skip-benchmark))
              {:keys [prompt-fn model-id]}
              (when needs-llm
                (llm/wrap-as-prompt-fn-from-opts {:provider provider :model model}))
              results   (atom {})
              t0        (System/currentTimeMillis)]
          (when-not (and skip-import skip-enrich)
            (run-digest-step! results :update "import + enrich"
                              #(sync/update-repo! conn repo-path repo-uri
                                                  {:concurrency (or concurrency 8)})))
          (when-not skip-analyze
            (run-digest-step! results :analyze "analyze"
                              #(analyze/analyze-repo! conn repo-path prompt-fn
                                                      {:model-id model-id
                                                       :concurrency (or concurrency 3)})))
          (when-not skip-benchmark
            (run-digest-step! results :benchmark "benchmark"
                              #(let [db   (d/db conn)
                                     mode (cond-> {} layers (assoc :layers layers))]
                                 (select-keys
                                  (bench/run-benchmark! db repo-path prompt-fn
                                                        :conn conn :mode mode
                                                        :budget {:max-questions max-questions}
                                                        :report? report
                                                        :concurrency (or concurrency 3))
                                  [:run-id :aggregate :stop-reason :report-path]))))
          (log! (str "digest: complete (" (- (System/currentTimeMillis) t0) " ms)"))
          {:exit 0 :result @results})
        (catch Exception e
          (print-error! (.getMessage e))
          {:exit 1})))))

;; --- Introspect ---

(defn do-introspect
  "Run the introspect self-improvement loop. Returns {:exit n :result map-or-nil}."
  [{:keys [model provider max-iterations max-hours max-cost git-commit target eval-runs] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [db db-name]}]
            (let [meta-conn (db/connect-and-ensure-schema
                             (util/resolve-db-dir opts) "noumenon-internal")
                  {:keys [invoke-fn]}
                  (llm/make-messages-fn-from-opts {:provider    provider
                                                   :model       model
                                                   :temperature 0.7
                                                   :max-tokens  8192})
                  invoke-fn-factory
                  (fn []
                    (:invoke-fn
                     (llm/make-messages-fn-from-opts {:provider    provider
                                                      :model       model
                                                      :temperature 0.0
                                                      :max-tokens  4096})))
                  result (introspect/run-loop!
                          {:db                  db
                           :repo-name           db-name
                           :repo-path           (:repo-path opts)
                           :meta-conn           meta-conn
                           :invoke-fn-factory   invoke-fn-factory
                           :optimizer-invoke-fn invoke-fn
                           :max-iterations      (or max-iterations 10)
                           :max-hours           max-hours
                           :max-cost            max-cost
                           :git-commit?         git-commit
                           :model-config        {:provider provider :model model}
                           :eval-runs           (or eval-runs 1)
                           :allowed-targets     (when target
                                                  (set (map keyword
                                                            (str/split target #","))))})]
              (log! (str "\nIntrospect complete: " (:improvements result)
                         " improvements in " (:iterations result)
                         " iterations (final score: "
                         (format "%.3f" (:final-score result))
                         ", run-id: " (:run-id result) ")"))
              {:exit 0 :result result})))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          {:exit 1})))))

;; --- Error dispatch ---

(def ^:private error-messages
  {:no-args                      nil
   :unknown-subcommand           #(str "Unknown subcommand: " (:subcommand %)
                                       ". Run '" cli/program-name " --help' for available subcommands.")
   :no-repo-path                 "Missing <repo-path> argument."
   :query-missing-args           "Missing <query-name> and <repo-path> arguments."
   :missing-db-dir-value         "Missing value for --db-dir."
   :missing-delete-value          "Missing database name for --delete."
   :unknown-flag                 #(str "Unknown option: " (:flag %))
   :invalid-max-questions        #(str "Invalid --max-questions value: " (:value %))
   :missing-max-questions-value  "Missing value for --max-questions."
   :invalid-stop-after           #(str "Invalid --stop-after value: " (:value %))
   :missing-stop-after-value     "Missing value for --stop-after."
   :missing-model-value          "Missing value for --model."
   :missing-judge-model-value    "Missing value for --judge-model."
   :missing-provider-value       "Missing value for --provider."
   :invalid-provider             #(str "Invalid --provider value: " (:value %)
                                       ". Must be 'glm', 'claude', 'claude-api', or 'claude-cli'.")
   :invalid-max-cost             #(str "Invalid --max-cost value: " (:value %))
   :missing-max-cost-value       "Missing value for --max-cost."
   :invalid-concurrency          #(str "Invalid --concurrency value: " (:value %) ". Must be 1-20.")
   :missing-concurrency-value    "Missing value for --concurrency."
   :invalid-min-delay            #(str "Invalid --min-delay value: " (:value %) ". Must be >= 0.")
   :missing-min-delay-value      "Missing value for --min-delay."
   :ask-missing-args              "Missing required arguments for ask command."
   :ask-missing-question          "Missing -q <question> argument."
   :invalid-max-iterations       #(str "Invalid --max-iterations value: " (:value %))
   :missing-max-iterations-value "Missing value for --max-iterations."
   :missing-param-value          "Missing value for --param. Use --param key=value."
   :invalid-param-value          #(str "Invalid --param value: " (:value %) ". Expected key=value format.")
   :invalid-max-files            #(str "Invalid --max-files value: " (:value %))
   :missing-max-files-value      "Missing value for --max-files."
   :invalid-interval             #(str "Invalid --interval value: " (:value %) ". Must be a positive integer.")
   :missing-interval-value       "Missing value for --interval."
   :missing-layers-value         "Missing value for --layers. Example: --layers raw,full"
   :invalid-max-hours            #(str "Invalid --max-hours value: " (:value %))
   :missing-max-hours-value      "Missing value for --max-hours."
   :missing-target-value         "Missing value for --target."})

(def ^:private errors-with-global-usage
  #{:no-args :unknown-subcommand})

(def ^:private errors-with-subcommand-usage
  #{:no-repo-path :missing-db-dir-value :unknown-flag
    :ask-missing-question :ask-missing-args :query-missing-args
    :missing-param-value :invalid-param-value
    :invalid-concurrency :missing-concurrency-value
    :invalid-min-delay :missing-min-delay-value
    :invalid-max-iterations :missing-max-iterations-value
    :invalid-interval :missing-interval-value
    :missing-layers-value})

;; --- Entry point ---

(defn run
  "Main dispatch. Returns {:exit n :result map-or-nil}."
  [args]
  (let [parsed (cli/parse-args args)]
    (cond
      (:version parsed)
      (do (println (util/read-version)) {:exit 0})

      (:help parsed)
      (let [h (:help parsed)]
        (println (if (= :global h)
                   (cli/format-global-help)
                   (cli/format-subcommand-help h)))
        {:exit 0})

      (:error parsed)
      (do (when-let [msg-or-fn (error-messages (:error parsed))]
            (let [msg (if (fn? msg-or-fn) (msg-or-fn parsed) msg-or-fn)]
              (print-error! msg)))
          (cond
            (errors-with-global-usage (:error parsed))
            (print-usage!)
            (and (errors-with-subcommand-usage (:error parsed))
                 (:subcommand parsed)
                 (cli/format-subcommand-help (:subcommand parsed)))
            (do (log!)
                (log! (cli/format-subcommand-help (:subcommand parsed))))
            (errors-with-subcommand-usage (:error parsed))
            (print-usage!))
          {:exit 1})

      :else
      (let [result (case (:subcommand parsed)
                     "import"         (do-import parsed)
                     "analyze"        (do-analyze parsed)
                     "enrich"         (do-enrich parsed)
                     "update"         (do-update parsed)
                     "watch"          (do-watch parsed)
                     "query"          (do-query parsed)
                     "ask"            (do-ask parsed)
                     "show-schema"    (do-show-schema parsed)
                     "status"         (do-status parsed)
                     "list-databases" (do-list-databases parsed)
                     "benchmark"      (do-benchmark parsed)
                     "digest"         (do-digest parsed)
                     "introspect"     (do-introspect parsed)
                     "serve"          (do (mcp/serve! parsed) {:exit 0}))]
        (when (and (:result result)
                   (zero? (:exit result))
                   (not (#{"benchmark" "serve" "status" "list-databases"
                           "show-schema" "watch" "introspect"} (:subcommand parsed))))
          (prn (:result result)))
        result))))

(defn -main [& args]
  (let [{:keys [exit]} (run args)]
    (System/exit exit)))
