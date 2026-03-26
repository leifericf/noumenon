(ns noumenon.main
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.benchmark :as bench]
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

(defn- parse-benchmark-args
  "Parse benchmark-specific CLI args."
  [args]
  (loop [remaining args
         opts      {:subcommand "benchmark"}
         positional []]
    (cond
      (empty? remaining)
      (if (seq positional)
        (assoc opts :repo-path (first positional))
        {:error :no-repo-path :subcommand "benchmark"})

      (= "--resume" (first remaining))
      (let [next-arg (second remaining)]
        (if (or (nil? next-arg) (str/starts-with? next-arg "-"))
          (recur (rest remaining) (assoc opts :resume "latest") positional)
          (recur (drop 2 remaining) (assoc opts :resume next-arg) positional)))

      (= "--max-questions" (first remaining))
      (if-let [v (second remaining)]
        (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
          (if (and n (pos? n))
            (recur (drop 2 remaining) (assoc opts :max-questions n) positional)
            {:error :invalid-max-questions :value v}))
        {:error :missing-max-questions-value})

      (= "--stop-after" (first remaining))
      (if-let [v (second remaining)]
        (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
          (if (and n (pos? n))
            (recur (drop 2 remaining) (assoc opts :stop-after n) positional)
            {:error :invalid-stop-after :value v}))
        {:error :missing-stop-after-value})

      (= "--max-cost" (first remaining))
      (if-let [v (second remaining)]
        (let [n (try (Double/parseDouble v) (catch Exception _ nil))]
          (if (and n (pos? n))
            (recur (drop 2 remaining) (assoc opts :max-cost n) positional)
            {:error :invalid-max-cost :value v}))
        {:error :missing-max-cost-value})

      (= "--model" (first remaining))
      (if (second remaining)
        (recur (drop 2 remaining) (assoc opts :model (second remaining)) positional)
        {:error :missing-model-value})

      (= "--judge-model" (first remaining))
      (if (second remaining)
        (recur (drop 2 remaining) (assoc opts :judge-model (second remaining)) positional)
        {:error :missing-judge-model-value})

      (= "--provider" (first remaining))
      (if-let [v (second remaining)]
        (if (#{"claude" "glm"} v)
          (recur (drop 2 remaining) (assoc opts :provider v) positional)
          {:error :invalid-provider :value v})
        {:error :missing-provider-value})

      (= "--concurrency" (first remaining))
      (if-let [v (second remaining)]
        (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
          (if (and n (<= 1 n 20))
            (recur (drop 2 remaining) (assoc opts :concurrency n) positional)
            {:error :invalid-concurrency :value v}))
        {:error :missing-concurrency-value})

      (= "--min-delay" (first remaining))
      (if-let [v (second remaining)]
        (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
          (if (and n (>= n 0))
            (recur (drop 2 remaining) (assoc opts :min-delay n) positional)
            {:error :invalid-min-delay :value v}))
        {:error :missing-min-delay-value})

      (= "--skip-raw" (first remaining))
      (recur (rest remaining) (assoc opts :skip-raw true) positional)

      (= "--skip-judge" (first remaining))
      (recur (rest remaining) (assoc opts :skip-judge true) positional)

      (= "--fast" (first remaining))
      (recur (rest remaining) (assoc opts :skip-raw true :skip-judge true) positional)

      (= "--canary" (first remaining))
      (recur (rest remaining) (assoc opts :canary true) positional)

      (= "--db-dir" (first remaining))
      (if (second remaining)
        (recur (drop 2 remaining) (assoc opts :db-dir (second remaining)) positional)
        {:error :missing-db-dir-value})

      (str/starts-with? (first remaining) "-")
      {:error :unknown-flag :flag (first remaining)}

      :else
      (recur (rest remaining) opts (conj positional (first remaining))))))

(defn- parse-longbench-run-args
  "Parse longbench run arguments."
  [args]
  (loop [remaining args
         opts      {:subcommand "longbench" :longbench-command "run"}]
    (if (empty? remaining)
      opts
      (let [[flag & more] remaining]
        (case flag
          "--resume"
          (let [next-arg (first more)]
            (if (or (nil? next-arg) (str/starts-with? next-arg "-"))
              (recur more (assoc opts :resume "latest"))
              (recur (rest more) (assoc opts :resume next-arg))))

          "--max-questions"
          (if-let [v (first more)]
            (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
              (if (and n (pos? n))
                (recur (rest more) (assoc opts :max-questions n))
                {:error :invalid-max-questions :value v}))
            {:error :missing-max-questions-value})

          "--stop-after"
          (if-let [v (first more)]
            (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
              (if (and n (pos? n))
                (recur (rest more) (assoc opts :stop-after n))
                {:error :invalid-stop-after :value v}))
            {:error :missing-stop-after-value})

          "--max-cost"
          (if-let [v (first more)]
            (let [n (try (Double/parseDouble v) (catch Exception _ nil))]
              (if (and n (pos? n))
                (recur (rest more) (assoc opts :max-cost n))
                {:error :invalid-max-cost :value v}))
            {:error :missing-max-cost-value})

          "--model"
          (if (first more)
            (recur (rest more) (assoc opts :model (first more)))
            {:error :missing-model-value})

          "--provider"
          (if-let [v (first more)]
            (if (#{"glm" "claude-api" "claude-cli"} v)
              (recur (rest more) (assoc opts :provider v))
              {:error :invalid-provider :value v})
            {:error :missing-provider-value})

          "--concurrency"
          (if-let [v (first more)]
            (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
              (if (and n (<= 1 n 20))
                (recur (rest more) (assoc opts :concurrency n))
                {:error :invalid-concurrency :value v}))
            {:error :missing-concurrency-value})

          "--min-delay"
          (if-let [v (first more)]
            (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
              (if (and n (>= n 0))
                (recur (rest more) (assoc opts :min-delay n))
                {:error :invalid-min-delay :value v}))
            {:error :missing-min-delay-value})

          ;; Unknown flag
          (if (str/starts-with? flag "-")
            {:error :unknown-flag :flag flag}
            (recur more opts)))))))

(defn- parse-longbench-results-args
  "Parse longbench results arguments."
  [args]
  (loop [remaining args
         opts      {:subcommand "longbench" :longbench-command "results"}]
    (if (empty? remaining)
      opts
      (let [[flag & more] remaining]
        (case flag
          "--detail" (recur more (assoc opts :detail true))
          (if (str/starts-with? flag "-")
            {:error :unknown-flag :flag flag}
            (recur more (assoc opts :run-id flag))))))))

(defn- parse-longbench-args
  "Parse longbench-specific CLI args."
  [args]
  (if (empty? args)
    {:error :longbench-no-subcommand :subcommand "longbench"}
    (let [[sub & rest-args] args]
      (case sub
        "download" {:subcommand "longbench" :longbench-command "download"}
        "run"      (parse-longbench-run-args rest-args)
        "results"  (parse-longbench-results-args rest-args)
        {:error :longbench-unknown-subcommand :subcommand "longbench"
         :longbench-command sub}))))

(defn- parse-agent-args
  "Parse agent-specific CLI args: agent <question> <repo-path> [options]."
  [args]
  (loop [remaining args
         opts      {:subcommand "agent"}
         positional []]
    (cond
      (empty? remaining)
      (cond
        (< (count positional) 2)
        {:error :agent-missing-args :subcommand "agent"}
        :else
        (assoc opts :question (first positional) :repo-path (second positional)))

      (= "--max-iterations" (first remaining))
      (if-let [v (second remaining)]
        (let [n (try (Integer/parseInt v) (catch Exception _ nil))]
          (if (and n (pos? n))
            (recur (drop 2 remaining) (assoc opts :max-iterations n) positional)
            {:error :invalid-max-iterations :value v}))
        {:error :missing-max-iterations-value})

      (= "--max-cost" (first remaining))
      (if-let [v (second remaining)]
        (let [n (try (Double/parseDouble v) (catch Exception _ nil))]
          (if (and n (pos? n))
            (recur (drop 2 remaining) (assoc opts :max-cost n) positional)
            {:error :invalid-max-cost :value v}))
        {:error :missing-max-cost-value})

      (= "--model" (first remaining))
      (if (second remaining)
        (recur (drop 2 remaining) (assoc opts :model (second remaining)) positional)
        {:error :missing-model-value})

      (= "--provider" (first remaining))
      (if-let [v (second remaining)]
        (if (#{"glm" "claude-api" "claude-cli"} v)
          (recur (drop 2 remaining) (assoc opts :provider v) positional)
          {:error :invalid-provider :value v})
        {:error :missing-provider-value})

      (= "-v" (first remaining))
      (recur (rest remaining) (assoc opts :verbose true) positional)

      (= "--db-dir" (first remaining))
      (if (second remaining)
        (recur (drop 2 remaining) (assoc opts :db-dir (second remaining)) positional)
        {:error :missing-db-dir-value})

      (str/starts-with? (first remaining) "-")
      {:error :unknown-flag :flag (first remaining)}

      :else
      (recur (rest remaining) opts (conj positional (first remaining))))))

(defn- parse-args
  "Parse CLI args into {:subcommand s, :repo-path p, :db-dir d} or {:error keyword}."
  [args]
  (if (empty? args)
    {:error :no-args}
    (let [[sub & rest-args] args]
      (case sub
        "benchmark" (parse-benchmark-args rest-args)
        "longbench" (parse-longbench-args rest-args)
        "agent"     (parse-agent-args rest-args)
        (if-not (#{"import" "status" "analyze" "query"} sub)
          {:error :unknown-subcommand :subcommand sub}
          (loop [remaining rest-args
                 opts {}
                 positional []]
            (cond
              (empty? remaining)
              (if (= "query" sub)
                (cond
                  (< (count positional) 2)
                  {:error :query-missing-args}
                  :else
                  (assoc opts :subcommand sub
                         :query-name (first positional)
                         :repo-path (second positional)))
                (if (seq positional)
                  (assoc opts :subcommand sub :repo-path (first positional))
                  {:error :no-repo-path :subcommand sub}))

              (= "--model" (first remaining))
              (if (second remaining)
                (recur (drop 2 remaining) (assoc opts :model (second remaining)) positional)
                {:error :missing-model-value})

              (= "--provider" (first remaining))
              (if-let [v (second remaining)]
                (if (#{"claude" "glm"} v)
                  (recur (drop 2 remaining) (assoc opts :provider v) positional)
                  {:error :invalid-provider :value v})
                {:error :missing-provider-value})

              (= "--db-dir" (first remaining))
              (if (second remaining)
                (recur (drop 2 remaining) (assoc opts :db-dir (second remaining)) positional)
                {:error :missing-db-dir-value})

              (str/starts-with? (first remaining) "-")
              {:error :unknown-flag :flag (first remaining)}

              :else
              (recur (rest remaining) opts (conj positional (first remaining))))))))))

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
             "  --provider <name>     Provider: glm (default) or claude"
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
             "  --provider <name>     Provider: glm (default), claude-api, or claude-cli"
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

(defn do-import
  "Run the import subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (let [db-dir  (resolve-db-dir opts)
          db-name (derive-db-name repo-path)
          conn    (db/connect-and-ensure-schema db-dir db-name)
          git-r   (git/import-commits! conn repo-path)
          files-r (files/import-files! conn repo-path)
          db-path (.getAbsolutePath (io/file db-dir "noumenon" db-name))]
      {:exit   0
       :result (merge (select-keys git-r [:commits-imported :commits-skipped])
                      (select-keys files-r [:files-imported :files-skipped :dirs-imported])
                      {:db-path db-path})})))

(defn do-analyze
  "Run the analyze subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path model provider] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (try
      (let [db-dir      (resolve-db-dir opts)
            db-name     (derive-db-name repo-path)
            provider-kw (case (or provider "claude")
                          "glm" :glm "claude" :claude-cli
                          "claude-api" :claude-api :claude-cli)
            invoke-llm  (llm/make-prompt-fn
                         (llm/make-invoke-fn provider-kw {:model model}))]
        (if-not (db-exists? db-dir db-name)
          (do (print-error! (str "No database found for \"" db-name
                                 "\". Run `import` first."))
              {:exit 1})
          (let [conn   (db/connect-and-ensure-schema db-dir db-name)
                result (analyze/analyze-repo! conn repo-path invoke-llm)]
            {:exit   0
             :result result})))
      (catch clojure.lang.ExceptionInfo e
        (print-error! (.getMessage e))
        {:exit 1}))))

(defn do-query
  "Run the query subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path query-name] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (let [db-dir  (resolve-db-dir opts)
          db-name (derive-db-name repo-path)]
      (if-not (db-exists? db-dir db-name)
        (do (print-error! (str "No database found for \"" db-name
                               "\". Run `import` first."))
            {:exit 1})
        (let [conn (db/connect-and-ensure-schema db-dir db-name)
              db   (d/db conn)
              {:keys [ok error]} (query/run-named-query db query-name)]
          (if error
            (do (print-error! error) {:exit 1})
            {:exit 0 :result ok}))))))

(defn do-agent
  "Run the agent subcommand. Returns {:exit n :result map-or-nil}.
   Unlike other subcommands, agent only needs the DB — the repo need not exist on disk."
  [{:keys [repo-path question model provider max-iterations verbose] :as opts}]
  (try
    (let [db-dir      (resolve-db-dir opts)
          db-name     (derive-db-name repo-path)
          provider-kw (keyword (or provider "glm"))
          model-id    (llm/model-alias->id (or model "sonnet"))]
      (if-not (db-exists? db-dir db-name)
        (do (print-error! (str "No database found for \"" db-name
                               "\". Run `import` first."))
            {:exit 1})
        (let [conn      (db/connect-and-ensure-schema db-dir db-name)
              db        (d/db conn)
              invoke-fn (llm/make-invoke-fn provider-kw
                                            {:model       model-id
                                             :temperature 0.3
                                             :max-tokens  4096})
              agent-opts (cond-> {:invoke-fn invoke-fn
                                  :repo-name db-name}
                           max-iterations (assoc :max-iterations max-iterations))
              result    (agent/ask db question agent-opts)]
          (when verbose
            (binding [*out* *err*]
              (doseq [step (:steps result)]
                (println (str "agent/step iteration=" (:iteration step)
                              (when (:tool-result step) " tool-result-size=")
                              (when (:tool-result step) (count (:tool-result step)))
                              (when (:error step) (str " error=" (:error step)))
                              (when (:answer step) " answer=yes"))))))
          {:exit   0
           :result {:answer (:answer result)
                    :status (:status result)
                    :usage  (:usage result)}})))
    (catch clojure.lang.ExceptionInfo e
      (print-error! (.getMessage e))
      {:exit 1})))

(defn do-status
  "Run the status subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path] :as opts}]
  (let [db-dir  (resolve-db-dir opts)
        db-name (derive-db-name repo-path)]
    (if-not (db-exists? db-dir db-name)
      (do (print-error! (str "No database found for \"" db-name
                             "\". Run `import` first."))
          {:exit 1})
      (let [conn    (db/connect-and-ensure-schema db-dir db-name)
            db      (d/db conn)
            commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
            files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
            dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))
            db-path (.getAbsolutePath (io/file db-dir "noumenon" db-name))]
        {:exit   0
         :result {:commits commits
                  :files   files
                  :dirs    dirs
                  :db-path db-path}}))))

(defn do-benchmark
  "Run the benchmark subcommand. Returns {:exit n}.
   Nothing to stdout; progress/results to stderr. Exit 0 on success/budget, 1 on permanent error, 2 on transient error."
  [{:keys [repo-path resume max-questions stop-after max-cost model judge-model provider
           concurrency min-delay skip-raw skip-judge canary] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (try
      (let [db-dir         (resolve-db-dir opts)
            db-name        (derive-db-name repo-path)
            checkpoint-dir "data/benchmarks/runs"
            provider       (or provider "glm")
            provider-kw    (case provider
                             "glm"       :glm
                             "claude"    :claude-cli
                             "claude-api" :claude-api
                             "claude-cli" :claude-cli
                             :claude-cli)
            model-config   {:model       model
                            :judge-model (or judge-model model)
                            :provider    provider}
            answer-llm     (llm/make-prompt-fn
                            (llm/make-invoke-fn provider-kw {:model model}))
            judge-llm      (llm/make-prompt-fn
                            (llm/make-invoke-fn provider-kw {:model (or judge-model model)}))
            budget         {:max-questions max-questions
                            :stop-after-ms (when stop-after (* stop-after 1000))
                            :max-cost-usd  max-cost}
            mode           (cond-> {}
                             skip-raw   (assoc :skip-raw true)
                             skip-judge (assoc :skip-judge true))]
        (if-not (db-exists? db-dir db-name)
          (do (print-error! (str "No database found for \"" db-name
                                 "\". Run `import` first."))
              {:exit 1})
          (let [conn (db/connect-and-ensure-schema db-dir db-name)
                db   (d/db conn)]
            (if resume
              ;; Resume flow
              (let [cp-path (bench/find-checkpoint checkpoint-dir resume)]
                (if-not cp-path
                  (do (print-error! (if (= "latest" resume)
                                      "No checkpoint files found"
                                      (str "Checkpoint not found: " resume)))
                      {:exit 1})
                  (let [cp     (try (bench/checkpoint-read cp-path)
                                    (catch Exception e
                                      (print-error! (str "Failed to parse checkpoint: "
                                                         (.getMessage e)))
                                      nil))]
                    (if-not cp
                      {:exit 1}
                      (let [questions (bench/load-questions)
                            rubric   (bench/load-rubric)
                            config   {:repo-path          (str repo-path)
                                      :commit-sha         (bench/repo-head-sha repo-path)
                                      :question-set-hash  (bench/question-set-hash questions)
                                      :model-config       model-config
                                      :mode               mode
                                      :rubric-hash        (bench/question-set-hash
                                                           (:judge-template rubric))
                                      :answer-prompt-hash (bench/question-set-hash
                                                           (bench/answer-prompt "{{q}}" "{{ctx}}"))}
                            compat (bench/validate-resume-compatibility cp config)]
                        (if-not (:ok compat)
                          (do (print-error!
                               (str "Incompatible checkpoint. Mismatched fields:\n"
                                    (str/join "\n"
                                              (map #(str "  " (name (:field %))
                                                         ": checkpoint=" (:checkpoint %)
                                                         " current=" (:current %))
                                                   (:mismatches compat)))))
                              {:exit 1})
                          (try
                            (bench/run-benchmark! db repo-path answer-llm
                                                  :judge-llm judge-llm
                                                  :model-config model-config
                                                  :checkpoint-dir checkpoint-dir
                                                  :resume-checkpoint cp
                                                  :budget budget
                                                  :mode mode
                                                  :canary canary
                                                  :concurrency (or concurrency 4)
                                                  :min-delay-ms (or min-delay 0))
                            {:exit 0}
                            (catch Exception e
                              (binding [*out* *err*]
                                (println (str "bench/error " (.getMessage e)))
                                (println (str "Resume with: clj -M:run benchmark "
                                              repo-path " --resume")))
                              {:exit 2}))))))))
              ;; Fresh run
              (try
                (bench/run-benchmark! db repo-path answer-llm
                                      :judge-llm judge-llm
                                      :model-config model-config
                                      :checkpoint-dir checkpoint-dir
                                      :budget budget
                                      :mode mode
                                      :concurrency (or concurrency 4)
                                      :min-delay-ms (or min-delay 0))
                {:exit 0}
                (catch Exception e
                  (binding [*out* *err*]
                    (println (str "bench/error " (.getMessage e)))
                    (println (str "Resume with: clj -M:run benchmark "
                                  repo-path " --resume")))
                  {:exit 2}))))))
      (catch clojure.lang.ExceptionInfo e
        (print-error! (.getMessage e))
        {:exit 1}))))

(defn- do-longbench-run
  "Run the longbench run subcommand."
  [{:keys [resume max-questions stop-after max-cost model provider concurrency min-delay]}]
  (try
    (let [provider-kw    (keyword (or provider "glm"))
          model-id       (llm/model-alias->id (or model "sonnet"))
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
  "Show results from a longbench run."
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

(def ^:private error-messages
  "Map of error keywords to message functions. Each value is either a string
   or a fn of parsed-args -> string."
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
   :invalid-provider             #(str "Invalid --provider value: " (:value %) ". Must be 'claude' or 'glm'.")
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
  "Error keywords that should also print usage text."
  #{:no-args :unknown-subcommand :no-repo-path :missing-db-dir-value :unknown-flag})

;; --- Entry point ---

(defn run
  "Main dispatch. Returns {:exit n :result map-or-nil}.
   Prints errors/usage to stderr, result EDN to stdout.
   Benchmark subcommand prints nothing to stdout."
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
