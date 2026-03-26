(ns noumenon.cli
  (:require [clojure.string :as str]
            [noumenon.llm :as llm]))

;; --- Coercion / validation registry ---

(defn- try-parse-int [s]
  (try (Integer/parseInt s) (catch Exception _ nil)))

(defn- try-parse-double [s]
  (try (Double/parseDouble s) (catch Exception _ nil)))

(def ^:private parser-registry
  {:string      {:coerce identity
                 :valid? (constantly true)}
   :pos-int     {:coerce try-parse-int
                 :valid? pos?}
   :non-neg-int {:coerce try-parse-int
                 :valid? #(>= % 0)}
   :pos-double  {:coerce try-parse-double
                 :valid? pos?}
   :range-int   {:coerce try-parse-int
                 :valid? (fn [n {:keys [min max]}]
                           (<= min n max))}})

(defn- parse-value
  "Parse and validate one value using parser-registry.
   Returns {:ok v} or {:error kw :value raw}."
  [spec raw]
  (let [{:keys [coerce valid?]} (parser-registry (:parse spec))
        value (coerce raw)
        in-range? (when value
                    (if (= :range-int (:parse spec))
                      (valid? value spec)
                      (valid? value)))]
    (if in-range?
      {:ok value}
      {:error (:error-invalid spec) :value raw})))

(defn- provider-set
  "Build a provider set that supports canonical values plus optional aliases."
  [& values]
  (set values))

(def ^:private all-valid-providers
  (provider-set "glm" "claude" "claude-api" "claude-cli"))

;; --- Shared flag specs ---

(def ^:private common-flags
  [{:flag "--model" :key :model :parse :string
    :desc "Model alias (e.g. sonnet, haiku, opus)"
    :error-missing :missing-model-value}
   {:flag "--provider" :key :provider :parse :string
    :desc "Provider: glm (default), claude-api, claude-cli (alias: claude)"
    :error-invalid :invalid-provider :error-missing :missing-provider-value}
   {:flag "--max-cost" :key :max-cost :parse :pos-double
    :desc "Stop when session cost exceeds threshold (dollars)"
    :error-invalid :invalid-max-cost :error-missing :missing-max-cost-value}
   {:flag "--db-dir" :key :db-dir :parse :string
    :desc "Override storage directory (default: data/datomic/)"
    :error-missing :missing-db-dir-value}
   {:flag "--verbose" :key :verbose :parse :bool
    :desc "Verbose output to stderr"}
   {:flag "-v" :key :verbose :parse :bool}])

(def ^:private concurrency-flags
  [{:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
    :desc "Parallel workers, 1-20 (default: 4)"
    :error-invalid :invalid-concurrency :error-missing :missing-concurrency-value}
   {:flag "--min-delay" :key :min-delay :parse :non-neg-int
    :desc "Min delay between LLM requests in ms (default: 0)"
    :error-invalid :invalid-min-delay :error-missing :missing-min-delay-value}])

(def ^:private budget-flags
  [{:flag "--max-questions" :key :max-questions :parse :pos-int
    :desc "Stop after n questions"
    :error-invalid :invalid-max-questions :error-missing :missing-max-questions-value}
   {:flag "--stop-after" :key :stop-after :parse :pos-int
    :desc "Stop after n seconds"
    :error-invalid :invalid-stop-after :error-missing :missing-stop-after-value}])

(def ^:private resume-flag
  {:flag "--resume" :key :resume :parse :optional-string
   :desc "Resume from checkpoint (default: latest)"})

(defn- with-provider-valid
  [specs valid-set]
  (mapv #(if (= "--provider" (:flag %)) (assoc % :valid valid-set) %) specs))

;; --- Declarative command specs ---

(def ^:private simple-command-spec
  {:flags (with-provider-valid common-flags all-valid-providers)
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private query-command-spec
  {:flags (with-provider-valid common-flags all-valid-providers)
   :initial {:subcommand "query"}
   :positionals {:required 2 :error :query-missing-args :keys [:query-name :repo-path]}})

(def ^:private benchmark-command-spec
  {:flags (vec (concat (with-provider-valid common-flags all-valid-providers)
                       concurrency-flags
                       budget-flags
                       [resume-flag
                        {:flag "--judge-model" :key :judge-model :parse :string
                         :desc "Model alias for judge stages"
                         :error-missing :missing-judge-model-value}
                        {:flag "--skip-raw" :key :skip-raw :parse :bool
                         :desc "Omit raw-context condition (halves LLM calls)"}
                        {:flag "--skip-judge" :key :skip-judge :parse :bool
                         :desc "Skip LLM judge stages (deterministic scores only)"}
                        {:flag "--fast" :key :fast :parse :bool
                         :desc "Sugar for --skip-raw --skip-judge"}
                        {:flag "--canary" :key :canary :parse :bool
                         :desc "Run q01+q02 first as canary; warn if both fail"}]))
   :initial {:subcommand "benchmark"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private longbench-run-command-spec
  {:flags (vec (concat [{:flag "--model" :key :model :parse :string
                         :desc "Model alias (e.g. sonnet, haiku, opus)"
                         :error-missing :missing-model-value}
                        {:flag "--provider" :key :provider :parse :string
                         :desc "Provider: glm (default), claude-api, claude-cli (alias: claude)"
                         :valid all-valid-providers
                         :error-invalid :invalid-provider
                         :error-missing :missing-provider-value}]
                       concurrency-flags
                       budget-flags
                       [resume-flag]))
   :initial {:subcommand "longbench" :longbench-command "run"}})

(def ^:private longbench-results-command-spec
  {:flags [{:flag "--detail" :key :detail :parse :bool
            :desc "Show per-question detail table"}]
   :initial {:subcommand "longbench" :longbench-command "results"}
   :positionals {:required 0 :error nil :keys [:run-id]}})

(def ^:private agent-command-spec
  {:flags [{:flag "-q" :key :question :parse :string
            :error-missing :agent-missing-question}
           {:flag "--question" :key :question :parse :string
            :desc "Question to ask about the repository"
            :error-missing :agent-missing-question}
           {:flag "--model" :key :model :parse :string
            :desc "Model alias (e.g. sonnet, haiku, opus)"
            :error-missing :missing-model-value}
           {:flag "--provider" :key :provider :parse :string
            :desc "Provider: glm (default), claude-api, claude-cli (alias: claude)"
            :valid all-valid-providers
            :error-invalid :invalid-provider
            :error-missing :missing-provider-value}
           {:flag "--max-iterations" :key :max-iterations :parse :pos-int
            :desc "Max query iterations (default: 10)"
            :error-invalid :invalid-max-iterations
            :error-missing :missing-max-iterations-value}
           {:flag "--max-cost" :key :max-cost :parse :pos-double
            :desc "Stop when session cost exceeds threshold (dollars)"
            :error-invalid :invalid-max-cost
            :error-missing :missing-max-cost-value}
           {:flag "--db-dir" :key :db-dir :parse :string
            :desc "Override storage directory (default: data/datomic/)"
            :error-missing :missing-db-dir-value}
           {:flag "--verbose" :key :verbose :parse :bool
            :desc "Log iterations to stderr"}
           {:flag "-v" :key :verbose :parse :bool}]
   :initial {:subcommand "agent"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

;; --- Command registry (drives help generation) ---

(def command-registry
  {"import"    {:spec simple-command-spec
                :summary "Import git history and file structure into Datomic"
                :usage "import [options] <repo-path>"}
   "analyze"   {:spec simple-command-spec
                :summary "Enrich imported files with LLM-driven semantic analysis"
                :usage "analyze [options] <repo-path>"}
   "query"     {:spec query-command-spec
                :summary "Run a named Datalog query against the knowledge graph"
                :usage "query [options] <query-name> <repo-path>"}
   "status"    {:spec simple-command-spec
                :summary "Show import counts for a repository"
                :usage "status [options] <repo-path>"}
   "agent"     {:spec agent-command-spec
                :summary "Ask a question about a repository using AI-powered querying"
                :usage "agent -q <question> [options] <repo-path>"}
   "benchmark" {:spec benchmark-command-spec
                :summary "Run benchmark suite against a repository"
                :usage "benchmark [options] <repo-path>"}
   "longbench" {:spec {:subcommands
                       {"download" {:summary "Download LongBench v2 dataset"}
                        "run"      {:spec longbench-run-command-spec
                                    :summary "Run LongBench v2 benchmark"
                                    :usage "longbench run [options]"}
                        "results"  {:spec longbench-results-command-spec
                                    :summary "Show results for a run"
                                    :usage "longbench results [options] [run-id]"}}}
                :summary "Run LongBench v2 standard benchmark"
                :usage "longbench <download|run|results> [options]"}})

(def ^:private command-order
  ["import" "analyze" "query" "status" "agent" "benchmark" "longbench"])

;; --- Help text generation ---

(defn- format-flag-line
  "Format one flag spec as a help line. Skips short-form aliases without :desc."
  [{:keys [flag desc]}]
  (when desc
    (format "  %-24s %s" flag desc)))

(defn- format-flags [flags]
  (->> flags (keep format-flag-line) (str/join "\n")))

(defn format-global-help []
  (str/join "\n"
            (concat
             ["Usage: clj -M:run <subcommand> [options]"
              ""
              "Subcommands:"]
             (mapv (fn [cmd]
                     (format "  %-12s %s" cmd (:summary (command-registry cmd))))
                   command-order)
             [""
              "Global options:"
              (format-flags common-flags)
              ""
              "Run `clj -M:run <subcommand> --help` for subcommand-specific options."])))

(defn format-subcommand-help [subcommand]
  (when-let [{:keys [spec usage summary]} (command-registry subcommand)]
    (if-let [subs (:subcommands spec)]
      ;; Nested subcommand (longbench)
      (str/join "\n"
                (concat
                 [(str summary)
                  ""
                  (str "Usage: clj -M:run " usage)
                  ""
                  "Subcommands:"]
                 (mapv (fn [[name {:keys [summary]}]]
                         (format "  %-12s %s" name summary))
                       subs)
                 (let [run-spec (get-in subs ["run" :spec])
                       res-spec (get-in subs ["results" :spec])]
                   (concat
                    (when (seq (:flags run-spec))
                      [""
                       "Run options:"
                       (format-flags (:flags run-spec))])
                    (when (seq (:flags res-spec))
                      [""
                       "Results options:"
                       (format-flags (:flags res-spec))])))))
      ;; Normal subcommand
      (str/join "\n"
                [(str summary)
                 ""
                 (str "Usage: clj -M:run " usage)
                 ""
                 "Options:"
                 (format-flags (:flags spec))]))))

;; --- Core parser ---

(defn- flag-map [flags]
  (into {} (map (juxt :flag identity)) flags))

(defn- parse-flags
  "Parse all flags in args. Returns [opts positional] or {:error ...}."
  [flags args initial]
  (let [lookup (flag-map flags)]
    (loop [remaining args
           opts initial
           positional []]
      (if (empty? remaining)
        [opts positional]
        (let [[arg & more] remaining]
          (if-let [spec (lookup arg)]
            (case (:parse spec)
              :bool
              (recur more (assoc opts (:key spec) true) positional)

              :optional-string
              (let [next-arg (first more)]
                (if (or (nil? next-arg) (str/starts-with? next-arg "-"))
                  (recur more (assoc opts (:key spec) "latest") positional)
                  (recur (rest more) (assoc opts (:key spec) next-arg) positional)))

              (let [raw (first more)]
                (if-not raw
                  {:error (:error-missing spec)}
                  (let [{:keys [ok error value]} (parse-value spec raw)]
                    (if error
                      {:error error :value value}
                      (if (and (:valid spec) (not ((:valid spec) raw)))
                        {:error (:error-invalid spec) :value raw}
                        (recur (rest more) (assoc opts (:key spec) ok) positional)))))))
            (if (str/starts-with? arg "-")
              {:error :unknown-flag :flag arg}
              (recur more opts (conj positional arg)))))))))

(defn- apply-positionals
  "Apply positional args by command spec. Returns opts or {:error ...}."
  [opts positional {:keys [required keys error]}]
  (if (or (nil? required) (>= (count positional) required))
    (reduce (fn [acc [k idx]]
              (if-let [v (nth positional idx nil)]
                (assoc acc k v)
                acc))
            opts
            (map vector keys (range)))
    {:error error}))

(defn- normalize-provider-opt
  [opts]
  (if-let [provider (:provider opts)]
    (assoc opts :provider (llm/normalize-provider-name provider))
    opts))

(defn- parse-command
  [command-spec args]
  (let [result (parse-flags (:flags command-spec) args (:initial command-spec))]
    (if (:error result)
      result
      (let [[opts positional] result
            opts* (if (:positionals command-spec)
                    (apply-positionals opts positional (:positionals command-spec))
                    opts)]
        (if (:error opts*)
          opts*
          (normalize-provider-opt opts*))))))

;; --- Help detection ---

(def ^:private help-flags #{"--help" "-h"})

(defn- contains-help? [args]
  (some help-flags args))

;; --- Subcommand parsers ---

(defn parse-benchmark-args [args]
  (if (contains-help? args)
    {:help "benchmark"}
    (let [result (parse-command benchmark-command-spec args)]
      (if (or (:error result) (not (:fast result)))
        result
        (-> result
            (assoc :skip-raw true :skip-judge true)
            (dissoc :fast))))))

(defn parse-longbench-run-args [args]
  (if (contains-help? args)
    {:help "longbench"}
    (parse-command longbench-run-command-spec args)))

(defn parse-longbench-results-args [args]
  (if (contains-help? args)
    {:help "longbench"}
    (parse-command longbench-results-command-spec args)))

(defn parse-longbench-args [args]
  (if (or (empty? args) (contains-help? args))
    (if (contains-help? args)
      {:help "longbench"}
      {:error :longbench-no-subcommand :subcommand "longbench"})
    (let [[sub & rest-args] args]
      (case sub
        "download" {:subcommand "longbench" :longbench-command "download"}
        "run"      (parse-longbench-run-args rest-args)
        "results"  (parse-longbench-results-args rest-args)
        {:error :longbench-unknown-subcommand
         :subcommand "longbench"
         :longbench-command sub}))))

(defn- validate-agent-question
  "Ensure agent opts include :question. Returns opts or {:error ...}."
  [opts]
  (if (:question opts)
    opts
    {:error :agent-missing-question}))

(defn parse-agent-args [args]
  (if (contains-help? args)
    {:help "agent"}
    (let [result (parse-command agent-command-spec args)]
      (if (:error result)
        result
        (validate-agent-question result)))))

(defn parse-simple-args
  "Parse args for import/status/analyze/query subcommands."
  [sub args]
  (if (contains-help? args)
    {:help sub}
    (if (= "query" sub)
      (parse-command query-command-spec args)
      (let [result (parse-command simple-command-spec args)]
        (assoc result :subcommand sub)))))

(defn parse-args
  "Top-level CLI arg parser. Returns opts map, {:help ...}, or {:error keyword ...}."
  [args]
  (let [first-arg (first args)]
    (cond
      (empty? args)
      {:error :no-args}

      (help-flags first-arg)
      {:help :global}

      (= "--version" first-arg)
      {:version true}

      :else
      (let [[sub & rest-args] args]
        (case sub
          "benchmark" (parse-benchmark-args rest-args)
          "longbench" (parse-longbench-args rest-args)
          "agent"     (parse-agent-args rest-args)
          (if (#{"import" "status" "analyze" "query"} sub)
            (parse-simple-args sub rest-args)
            {:error :unknown-subcommand :subcommand sub}))))))
