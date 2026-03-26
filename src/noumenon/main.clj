(ns noumenon.main
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.benchmark :as bench]
            [noumenon.db :as db]
            [noumenon.files :as files]
            [noumenon.git :as git]
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
      (if (= "benchmark" sub)
        (parse-benchmark-args rest-args)
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
             "  benchmark  Run benchmark suite against a repository"
             ""
             "Options:"
             "  --db-dir <dir>        Override default storage directory (default: data/datomic/)"
             "  --model <alias>       Model alias (e.g. sonnet, haiku)"
             "  --provider <name>     Provider: glm (default) or claude"
             ""
             "Benchmark options:"
             "  --resume [run-id]     Resume from checkpoint (default: latest)"
             "  --max-questions <n>   Stop after n questions"
             "  --stop-after <secs>   Stop after n seconds"
             "  --max-cost <dollars>  Stop when session cost exceeds threshold"
             "  --judge-model <alias> Model alias for judge stages"
             "  --concurrency <n>    Parallel pair workers, 1-20 (default: 4)"
             "  --min-delay <ms>     Min delay between LLM requests (default: 0)"]))

(defn- provider-env
  "Build env var map for the given provider. Throws if GLM token is missing."
  [provider]
  (when (= "glm" provider)
    (let [token (System/getenv "NOUMENON_ZAI_TOKEN")]
      (when-not token
        (throw (ex-info "NOUMENON_ZAI_TOKEN environment variable is not set"
                        {:provider "glm"})))
      {"ANTHROPIC_BASE_URL"   "https://api.z.ai/api/anthropic"
       "ANTHROPIC_AUTH_TOKEN" token})))

(defn- make-invoke-llm
  "Create an invoke-llm closure for the given model alias and env map."
  [model env]
  (fn [prompt]
    (analyze/invoke-claude-cli prompt (cond-> {}
                                        model (assoc :model model)
                                        env   (assoc :env env)))))

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
      (let [db-dir     (resolve-db-dir opts)
            db-name    (derive-db-name repo-path)
            env        (provider-env (or provider "glm"))
            invoke-llm (make-invoke-llm model env)]
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
           concurrency min-delay] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (try
      (let [db-dir         (resolve-db-dir opts)
            db-name        (derive-db-name repo-path)
            checkpoint-dir "data/benchmarks/runs"
            provider       (or provider "glm")
            model-config   {:model       model
                            :judge-model (or judge-model model)
                            :provider    provider}
            env            (provider-env provider)
            answer-llm     (make-invoke-llm model env)
            judge-llm      (make-invoke-llm (or judge-model model) env)
            budget         {:max-questions max-questions
                            :stop-after-ms (when stop-after (* stop-after 1000))
                            :max-cost-usd  max-cost}]
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

;; --- Entry point ---

(defn run
  "Main dispatch. Returns {:exit n :result map-or-nil}.
   Prints errors/usage to stderr, result EDN to stdout.
   Benchmark subcommand prints nothing to stdout."
  [args]
  (let [parsed (parse-args args)]
    (case (:error parsed)
      :no-args
      (do (print-usage!) {:exit 1})

      :unknown-subcommand
      (do (print-error! (str "Unknown subcommand: " (:subcommand parsed)))
          (print-usage!)
          {:exit 1})

      :no-repo-path
      (do (print-error! "Missing <repo-path> argument.")
          (print-usage!)
          {:exit 1})

      :query-missing-args
      (do (print-error! "Usage: query <query-name> <repo-path>")
          {:exit 1})

      :missing-db-dir-value
      (do (print-error! "Missing value for --db-dir.")
          (print-usage!)
          {:exit 1})

      :unknown-flag
      (do (print-error! (str "Unknown option: " (:flag parsed)))
          (print-usage!)
          {:exit 1})

      :invalid-max-questions
      (do (print-error! (str "Invalid --max-questions value: " (:value parsed)))
          {:exit 1})

      :missing-max-questions-value
      (do (print-error! "Missing value for --max-questions.")
          {:exit 1})

      :invalid-stop-after
      (do (print-error! (str "Invalid --stop-after value: " (:value parsed)))
          {:exit 1})

      :missing-stop-after-value
      (do (print-error! "Missing value for --stop-after.")
          {:exit 1})

      :missing-model-value
      (do (print-error! "Missing value for --model.")
          {:exit 1})

      :missing-judge-model-value
      (do (print-error! "Missing value for --judge-model.")
          {:exit 1})

      :missing-provider-value
      (do (print-error! "Missing value for --provider.")
          {:exit 1})

      :invalid-provider
      (do (print-error! (str "Invalid --provider value: " (:value parsed)
                             ". Must be 'claude' or 'glm'."))
          {:exit 1})

      :invalid-max-cost
      (do (print-error! (str "Invalid --max-cost value: " (:value parsed)))
          {:exit 1})

      :missing-max-cost-value
      (do (print-error! "Missing value for --max-cost.")
          {:exit 1})

      :invalid-concurrency
      (do (print-error! (str "Invalid --concurrency value: " (:value parsed)
                             ". Must be 1-20."))
          {:exit 1})

      :missing-concurrency-value
      (do (print-error! "Missing value for --concurrency.")
          {:exit 1})

      :invalid-min-delay
      (do (print-error! (str "Invalid --min-delay value: " (:value parsed)
                             ". Must be >= 0."))
          {:exit 1})

      :missing-min-delay-value
      (do (print-error! "Missing value for --min-delay.")
          {:exit 1})

      ;; no error — dispatch subcommand
      (let [result (case (:subcommand parsed)
                     "import"    (do-import parsed)
                     "analyze"   (do-analyze parsed)
                     "query"     (do-query parsed)
                     "status"    (do-status parsed)
                     "benchmark" (do-benchmark parsed))]
        (when (and (:result result) (not= "benchmark" (:subcommand parsed)))
          (prn (:result result)))
        result))))

(defn -main [& args]
  (let [{:keys [exit]} (run args)]
    (System/exit exit)))
