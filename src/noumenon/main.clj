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
            [noumenon.longbench :as longbench]
            [noumenon.mcp :as mcp]
            [noumenon.query :as query]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]]))

;; --- Helpers ---

(defn derive-db-name
  "Extract database name from a repo path: last path component, sanitized.
   Only alphanumeric, hyphen, underscore, and dot are kept. Rejects '..' and empty results."
  [repo-path]
  (let [raw (-> repo-path str (str/replace #"/+$" "") (str/split #"/") last)
        sanitized (str/replace raw #"[^a-zA-Z0-9\-_.]" "")]
    (when (and (seq sanitized) (not= ".." sanitized))
      sanitized)))

(defn resolve-db-dir
  "Resolve the database storage directory. Defaults to data/datomic/ relative to cwd."
  [opts]
  (or (:db-dir opts)
      (str (.getAbsolutePath (io/file "data" "datomic")))))

(defn- print-usage! []
  (log! (cli/format-global-help)))

(defn- print-error! [msg]
  (log! (str "Error: " msg)))

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
  (let [db-name (derive-db-name repo-path)]
    (when-not db-name
      (throw (ex-info (str "Cannot derive database name from path: " repo-path)
                      {:repo-path repo-path})))
    {:repo-path repo-path
     :db-dir    (resolve-db-dir opts)
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
        (log! (str "Next: run '" cli/program-name " analyze " repo-path
                   "' to add semantic metadata."))
        {:exit   0
         :result (merge (select-keys git-r [:commits-imported :commits-skipped])
                        (select-keys files-r [:files-imported :files-skipped :dirs-imported])
                        {:db-path    (db-path ctx)
                         :next-step  (str cli/program-name " analyze " repo-path)})}))))

(defn do-analyze
  "Run the analyze subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path model provider concurrency min-delay] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [conn]}]
            (let [provider-kw (llm/provider->kw
                               (or provider llm/default-provider))
                  model-id    (llm/model-alias->id
                               (or model llm/default-model-alias))
                  invoke-llm  (llm/make-prompt-fn
                               (llm/make-invoke-fn provider-kw {:model model-id}))]
              {:exit   0
               :result (analyze/analyze-repo! conn repo-path invoke-llm
                                              {:model-id     model-id
                                               :concurrency  (or concurrency 3)
                                               :min-delay-ms (or min-delay 0)})})))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          {:exit 1})))))

(defn do-postprocess
  "Run the postprocess subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [concurrency] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (with-existing-db
        ctx
        (fn [{:keys [conn repo-path]}]
          (let [result (imports/postprocess-repo! conn repo-path
                                                  {:concurrency (or concurrency 8)})]
            (log! (str "Next: run '" cli/program-name " query file-imports "
                       repo-path "' to explore the import graph."))
            {:exit 0 :result result}))))))

(defn do-sync
  "Run the sync subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [analyze model provider concurrency] :as opts}]
  (with-valid-repo
    (update opts :repo-path resolve-repo-path)
    (fn [{:keys [repo-path db-dir db-name]}]
      (let [conn     (db/connect-and-ensure-schema db-dir db-name)
            repo-uri (.getCanonicalPath (java.io.File. (str repo-path)))
            sync-opts (cond-> {:concurrency          (or concurrency 8)
                               :analyze-concurrency  (or concurrency 3)}
                        analyze
                        (assoc :analyze? true
                               :model-id (llm/model-alias->id
                                          (or model llm/default-model-alias))
                               :invoke-llm (llm/make-prompt-fn
                                            (llm/make-invoke-fn
                                             (llm/provider->kw
                                              (or provider llm/default-provider))
                                             {:model (llm/model-alias->id
                                                      (or model llm/default-model-alias))}))))
            result (sync/sync-repo! conn repo-path repo-uri sync-opts)]
        {:exit 0 :result result}))))

(defn do-watch
  "Run the watch subcommand. Polls git HEAD and syncs on changes."
  [{:keys [interval analyze model provider concurrency] :as opts}]
  (with-valid-repo
    opts
    (fn [{:keys [repo-path db-dir db-name]}]
      (let [conn       (db/connect-and-ensure-schema db-dir db-name)
            repo-uri   (.getCanonicalPath (java.io.File. (str repo-path)))
            interval-s (or interval 30)
            sync-opts  (cond-> {:concurrency          (or concurrency 8)
                                :analyze-concurrency  (or concurrency 3)}
                         analyze
                         (assoc :analyze? true
                                :model-id (llm/model-alias->id
                                           (or model llm/default-model-alias))
                                :invoke-llm (llm/make-prompt-fn
                                             (llm/make-invoke-fn
                                              (llm/provider->kw
                                               (or provider llm/default-provider))
                                              {:model (llm/model-alias->id
                                                       (or model llm/default-model-alias))}))))]
        (log! (str "Watching " repo-path " (polling every " interval-s "s)"))
        (loop []
          (try
            (sync/sync-repo! conn repo-path repo-uri sync-opts)
            (catch Exception e
              (log! (str "Sync error: " (.getMessage e)))))
          (Thread/sleep (* interval-s 1000))
          (recur))))))

(defn- do-query-list
  "List available named queries with descriptions."
  []
  (let [queries (->> (query/list-query-names)
                     (mapv (fn [qname]
                             {:name        qname
                              :description (or (:description (query/load-named-query qname)) "")})))]
    (doseq [{:keys [name description]} queries]
      (println (format "  %-28s %s" name description)))
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
            (let [{:keys [ok error]} (query/run-named-query db query-name params)]
              (if error
                (do (print-error! error)
                    (when (str/starts-with? (str error) "Missing required inputs")
                      (log! "Hint: use --param key=value to supply query inputs."))
                    {:exit 1})
                {:exit 0 :result ok}))))))))

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
                               (or provider llm/default-provider))
                  model-id    (llm/model-alias->id
                               (or model llm/default-model-alias))
                  invoke-fn   (llm/make-invoke-fn provider-kw
                                                  {:model       model-id
                                                   :temperature 0.3
                                                   :max-tokens  4096})
                  result      (agent/ask db question
                                         (cond-> {:invoke-fn invoke-fn :repo-name db-name}
                                           max-iterations (assoc :max-iterations max-iterations)))]
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
              {:exit   (if (= :budget-exhausted (:status result)) 2 0)
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
          (let [stats {:commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
                       :files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
                       :dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))
                       :db-path (db-path c)}]
            (log! (str (:commits stats) " commits, "
                       (:files stats) " files, "
                       (:dirs stats) " directories"
                       " -- db: " (:db-path stats)))
            {:exit 0 :result stats}))))))

;; --- Databases ---

(defn- list-db-dirs
  "Return sorted seq of database names found in the storage dir."
  [db-dir]
  (some->> (io/file db-dir "noumenon") .listFiles
           (filter #(.isDirectory %))
           (sort-by #(.getName %))
           (mapv #(.getName %))))

(defn- tx-op-counts
  "Return map of {:import n :analyze n :postprocess n} from tx metadata."
  [db]
  (->> (d/q '[:find ?op (count ?tx) :where [?tx :tx/op ?op]] db)
       (into {})))

(defn- db-stats
  "Connect to a DB and return stats map with counts, pipeline stages, and cost."
  [db-dir db-name]
  (try
    (let [db     (d/db (db/connect-and-ensure-schema db-dir db-name))
          latest (ffirst (d/q '[:find (max ?d) :where [_ :commit/committed-at ?d]] db))
          cost   (or (ffirst (d/q '[:find (sum ?c) :where [_ :tx/cost-usd ?c]] db)) 0.0)
          ops    (tx-op-counts db)]
      {:name    db-name
       :commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
       :files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
       :dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))
       :latest  latest
       :cost    cost
       :ops     ops})
    (catch Exception e
      {:name db-name :error (.getMessage e)})))

(defn- format-date [inst]
  (when inst
    (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") inst)))

(defn- format-pipeline
  "Format pipeline stages as [import:3 analyze:42 postprocess:1]."
  [ops]
  (let [stages (keep (fn [op]
                       (when-let [n (ops op)]
                         (str (clojure.core/name op) ":" n)))
                     [:import :analyze :postprocess])]
    (when (seq stages)
      (str "  [" (str/join " " stages) "]"))))

(defn- print-db-stats
  [{:keys [name commits files dirs latest cost ops error]}]
  (if error
    (println (format "  %-24s (error: %s)" name error))
    (let [date-str  (if latest (str "  (latest: " (format-date latest) ")") "")
          cost-str  (if (pos? cost) (format "  $%.2f" cost) "")
          stage-str (or (format-pipeline ops) "")]
      (println (format "  %-24s %d commits, %d files, %d dirs%s%s%s"
                       name commits files dirs date-str cost-str stage-str)))))

(defn do-databases
  "List all databases or delete one. Returns {:exit n :result vec-or-nil}."
  [opts]
  (let [db-dir (resolve-db-dir opts)]
    (if-let [db-name (:delete opts)]
      (let [client (db/create-client db-dir)]
        (if-not (db-exists? db-dir db-name)
          (do (print-error! (str "Database \"" db-name "\" not found.")) {:exit 1})
          (do (db/delete-db client db-name)
              (log! (str "Deleted database \"" db-name "\"."))
              (log! (str "Re-import: " cli/program-name " import <repo-path>"))
              {:exit 0})))
      (let [names (list-db-dirs db-dir)]
        (if (empty? names)
          (do (log! (str "No databases found in " db-dir)) {:exit 0 :result []})
          (let [stats (mapv #(db-stats db-dir %) names)]
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
                          :min-delay-ms (or (:min-delay opts) 0))
    {:exit 0}
    (catch Exception e
      (print-error! (.getMessage e))
      (log! (str "Resume with: " cli/program-name " benchmark " repo-path " --resume"))
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
      (let [field-labels {:repo-path          "Repository path"
                          :commit-sha         "Git HEAD commit"
                          :question-set-hash  "Question set"
                          :model-config       "Model configuration"
                          :mode               "Run mode"
                          :rubric-hash        "Rubric"
                          :answer-prompt-hash "Answer prompt"}]
        (print-error!
         (str "Incompatible checkpoint. Mismatched fields:\n"
              (str/join "\n"
                        (map #(str "  " (get field-labels (:field %) (name (:field %)))
                                   ": checkpoint=" (:checkpoint %)
                                   " current=" (:current %))
                             (:mismatches compat)))))
        {:exit 1})

      :else
      (run-benchmark-impl! db repo-path answer-llm
                           (assoc run-opts :resume-checkpoint cp)))))

(defn do-benchmark
  "Run the benchmark subcommand. Returns {:exit n}."
  [{:keys [resume max-questions stop-after max-cost model judge-model provider
           concurrency min-delay skip-raw skip-judge canary] :as opts}]
  (with-valid-repo
    opts
    (fn [ctx]
      (try
        (with-existing-db
          ctx
          (fn [{:keys [db]}]
            (let [checkpoint-dir "data/benchmarks/runs"
                  provider       (or provider llm/default-provider)
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
              (if resume
                (do-benchmark-resume checkpoint-dir resume db (:repo-path opts) answer-llm run-opts)
                (run-benchmark-impl! db (:repo-path opts) answer-llm run-opts)))))
        (catch clojure.lang.ExceptionInfo e
          (print-error! (.getMessage e))
          {:exit 1})))))

;; --- LongBench ---

(defn- do-longbench-run
  [{:keys [resume max-questions stop-after max-cost model provider concurrency min-delay]}]
  (try
    (let [provider-kw    (llm/provider->kw (or provider llm/default-provider))
          model-id       (llm/model-alias->id (or model llm/default-model-alias))
          checkpoint-dir "data/longbench/runs"
          invoke-llm     (llm/make-invoke-fn provider-kw
                                             {:model       model-id
                                              :temperature 0.1
                                              :max-tokens  128})
          budget         {:max-questions max-questions
                          :stop-after-ms (when stop-after (* stop-after 1000))
                          :max-cost-usd  max-cost}]
      (when (= :claude-cli provider-kw)
        (log! "longbench/param-deviation provider=claude-cli temperature=default max_tokens=default"))
      (if resume
        (let [cp-path (bench/find-checkpoint checkpoint-dir
                                             (if (string? resume) resume "latest"))]
          (if-not cp-path
            (do (print-error! (if (= "latest" resume)
                                "No checkpoint files found"
                                (str "Checkpoint not found: " resume)))
                {:exit 1})
            (let [cp (try (bench/checkpoint-read cp-path)
                          (catch Exception e
                            (print-error! (str "Failed to parse checkpoint: "
                                               (.getMessage e)))
                            nil))]
              (if-not cp
                {:exit 1}
                (do (longbench/run-longbench-resume! invoke-llm cp
                                                     :checkpoint-dir checkpoint-dir
                                                     :budget budget
                                                     :concurrency (or concurrency 3)
                                                     :min-delay-ms (or min-delay 0))
                    {:exit 0})))))
        (do
          (longbench/run-longbench! invoke-llm
                                    :checkpoint-dir checkpoint-dir
                                    :budget budget
                                    :concurrency (or concurrency 3)
                                    :min-delay-ms (or min-delay 0))
          {:exit 0})))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (#{429 500 502 503} (:status data))
          (do (print-error! (str "API error: " (.getMessage e)))
              {:exit 2})
          (do (print-error! (.getMessage e))
              {:exit 1}))))
    (catch Exception e
      (print-error! (.getMessage e))
      (log! (str "Resume with: " cli/program-name " longbench run --resume"))
      {:exit 2})))

(defn- print-longbench-detail!
  [results]
  (let [id-width (max 2 (apply max (map (comp count str :_id) results)))
        id-fmt   (str "%-" id-width "s")]
    (println)
    (println (format (str "  " id-fmt "  %-5s %-5s  %s")
                     "ID" "Pred" "Truth" "Correct?"))
    (println (format (str "  " id-fmt "  %-5s %-5s  %s")
                     (apply str (repeat id-width "-"))
                     "----" "-----" "--------"))
    (doseq [r results]
      (println (format (str "  " id-fmt "  %-5s %-5s  %s")
                       (:_id r)
                       (or (:pred r) "nil")
                       (:answer r)
                       (if (:judge r) "yes" "no"))))))

(defn- do-longbench-results
  [{:keys [run-id detail]}]
  (let [rid (or run-id
                (when-let [cp (longbench/find-latest-run)]
                  (-> (java.io.File. ^String cp) .getName
                      (str/replace #"\.edn$" ""))))]
    (cond
      (not rid)
      (do (print-error! "No runs found.") {:exit 1})

      :else
      (if-let [results (longbench/load-results rid)]
        (let [agg (longbench/aggregate-results
                   (mapv (fn [r] {:prediction (:pred r) :answer (:answer r)
                                  :difficulty (:difficulty r) :length (:length r)})
                         results))
              fmt (fn [v] (if v (format "%5.1f%%" (double v)) "  n/a "))]
          (println (str "Run: " rid))
          (println (str "Questions: " (:total agg)))
          (println)
          (println "Accuracy (%) by difficulty and length:")
          (println (format "  %6s  %6s  %6s  %6s  %6s  %6s"
                           "Score" "Easy" "Hard" "Short" "Medium" "Long"))
          (println (format "  %6s  %6s  %6s  %6s  %6s  %6s"
                           "------" "------" "------" "------" "------" "------"))
          (println (format "  %s  %s  %s  %s  %s  %s"
                           (fmt (:overall agg)) (fmt (:easy agg)) (fmt (:hard agg))
                           (fmt (:short agg)) (fmt (:medium agg)) (fmt (:long agg))))
          (when detail (print-longbench-detail! results))
          {:exit 0})
        (do (print-error! (str "No results found for run: " rid))
            {:exit 1})))))

(defn do-longbench
  "Run the longbench subcommand. Returns {:exit n}."
  [{:keys [longbench-command] :as opts}]
  (case longbench-command
    "download" (try
                 (log! "Downloading LongBench dataset...")
                 (longbench/download-dataset!)
                 (log! "Download complete.")
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
   :longbench-no-subcommand      "Missing longbench subcommand. Expected: download, run, or results."
   :longbench-unknown-subcommand #(str "Unknown longbench subcommand: " (:longbench-command %)
                                       ". Expected: download, run, or results.")
   :agent-missing-args           "Missing required arguments for agent command."
   :agent-missing-question        "Missing -q <question> argument."
   :invalid-max-iterations       #(str "Invalid --max-iterations value: " (:value %))
   :missing-max-iterations-value "Missing value for --max-iterations."
   :missing-param-value          "Missing value for --param. Use --param key=value."
   :invalid-param-value          #(str "Invalid --param value: " (:value %) ". Expected key=value format.")})

(def ^:private errors-with-global-usage
  #{:no-args})

(def ^:private errors-with-subcommand-usage
  #{:no-repo-path :missing-db-dir-value :unknown-flag
    :agent-missing-question :agent-missing-args :query-missing-args
    :missing-param-value :invalid-param-value})

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
                     "import"    (do-import parsed)
                     "analyze"      (do-analyze parsed)
                     "postprocess" (do-postprocess parsed)
                     "sync"        (do-sync parsed)
                     "watch"       (do-watch parsed)
                     "query"       (do-query parsed)
                     "agent"     (do-agent parsed)
                     "status"    (do-status parsed)
                     "databases" (do-databases parsed)
                     "benchmark" (do-benchmark parsed)
                     "longbench" (do-longbench parsed)
                     "serve"     (do (mcp/serve! parsed) {:exit 0}))]
        (when (and (:result result)
                   (zero? (:exit result))
                   (not (#{"benchmark" "longbench" "serve" "status" "databases" "watch"} (:subcommand parsed))))
          (prn (:result result)))
        result))))

(defn -main [& args]
  (let [{:keys [exit]} (run args)]
    (System/exit exit)))
