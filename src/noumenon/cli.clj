(ns noumenon.cli
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.llm :as llm]))

(def program-name
  (if (= "jar" (.getProtocol (io/resource "version.edn")))
    "noumenon"
    "clj -M:run"))

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

(def ^:private all-valid-providers
  #{"glm" "claude" "claude-api" "claude-cli"})

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
    :desc "Log verbose output to stderr"}
   {:flag "-v" :key :verbose :parse :bool}])

(def ^:private concurrency-flags
  [{:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
    :desc "Parallel workers, 1-20 (default: 3)"
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
   :desc "Resume from checkpoint (default: latest). Place before <repo-path> to avoid ambiguity."})

(defn- with-provider-valid
  [specs valid-set]
  (mapv #(if (= "--provider" (:flag %)) (assoc % :valid valid-set) %) specs))

;; --- Narrowly scoped flag sets ---

(def ^:private db-dir-flag
  {:flag "--db-dir" :key :db-dir :parse :string
   :desc "Override storage directory (default: data/datomic/)"
   :error-missing :missing-db-dir-value})

(def ^:private analyze-flags
  (vec (concat
        [{:flag "--model" :key :model :parse :string
          :desc "Model alias (e.g. sonnet, haiku, opus)"
          :error-missing :missing-model-value}
         {:flag "--provider" :key :provider :parse :string
          :desc "Provider: glm (default), claude-api, claude-cli (alias: claude)"
          :error-invalid :invalid-provider :error-missing :missing-provider-value}
         db-dir-flag
         {:flag "--verbose" :key :verbose :parse :bool
          :desc "Log verbose output to stderr"}
         {:flag "-v" :key :verbose :parse :bool}]
        concurrency-flags)))

;; --- Declarative command specs ---

(def ^:private simple-command-spec
  {:flags [db-dir-flag]
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private postprocess-command-spec
  {:flags [db-dir-flag
           {:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
            :desc "Parallel workers, 1-20 (default: 8)"
            :error-invalid :invalid-concurrency :error-missing :missing-concurrency-value}]
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private sync-command-spec
  {:flags (vec (concat
                [{:flag "--analyze" :key :analyze :parse :bool
                  :desc "Also run LLM analysis on changed files"}
                 {:flag "--model" :key :model :parse :string
                  :desc "Model alias (e.g. sonnet, haiku, opus)"
                  :error-missing :missing-model-value}
                 {:flag "--provider" :key :provider :parse :string
                  :desc "Provider: glm (default), claude-api, claude-cli (alias: claude)"
                  :valid all-valid-providers
                  :error-invalid :invalid-provider
                  :error-missing :missing-provider-value}
                 db-dir-flag
                 {:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
                  :desc "Parallel workers, 1-20 (default: 8)"
                  :error-invalid :invalid-concurrency :error-missing :missing-concurrency-value}]))
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private watch-command-spec
  {:flags [{:flag "--interval" :key :interval :parse :pos-int
            :desc "Polling interval in seconds (default: 30)"
            :error-invalid :invalid-interval :error-missing :missing-interval-value}
           {:flag "--analyze" :key :analyze :parse :bool
            :desc "Also run LLM analysis on changed files"}
           {:flag "--model" :key :model :parse :string
            :desc "Model alias (e.g. sonnet, haiku, opus)"
            :error-missing :missing-model-value}
           {:flag "--provider" :key :provider :parse :string
            :desc "Provider: glm (default), claude-api, claude-cli (alias: claude)"
            :valid all-valid-providers
            :error-invalid :invalid-provider
            :error-missing :missing-provider-value}
           db-dir-flag
           {:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
            :desc "Parallel workers, 1-20 (default: 8)"
            :error-invalid :invalid-concurrency :error-missing :missing-concurrency-value}]
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private databases-command-spec
  {:flags [db-dir-flag
           {:flag "--delete" :key :delete :parse :string
            :desc "Delete a database by name"
            :error-missing :missing-delete-value}]
   :initial {:subcommand "databases"}
   :positionals {:required 0 :error nil :keys []}})

(def ^:private analyze-command-spec
  {:flags (with-provider-valid analyze-flags all-valid-providers)
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private query-command-spec
  {:flags [db-dir-flag]
   :initial {:subcommand "query"}
   :positionals {:required 2 :error :query-missing-args :keys [:query-name :repo-path]}})

(def ^:private benchmark-flags
  "Common flags without --verbose (benchmark does not use it)."
  (->> common-flags
       (remove (comp #{:verbose} :key))
       vec))

(def ^:private benchmark-command-spec
  {:flags (vec (concat (with-provider-valid benchmark-flags all-valid-providers)
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
            :desc "Alias for --question"
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
           {:flag "--db-dir" :key :db-dir :parse :string
            :desc "Override storage directory (default: data/datomic/)"
            :error-missing :missing-db-dir-value}
           {:flag "--verbose" :key :verbose :parse :bool
            :desc "Log verbose output to stderr"}
           {:flag "-v" :key :verbose :parse :bool}]
   :initial {:subcommand "agent"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

;; --- Command registry (drives help generation) ---

(def command-registry
  {"import"    {:spec simple-command-spec
                :summary "Import git history and file structure into Datomic"
                :usage "import [options] <repo-path-or-url>"
                :epilog "Accepts a local path or a Git URL (https://, git@).\nURLs are auto-cloned to data/repos/<name>/."}
   "analyze"      {:spec analyze-command-spec
                   :summary "Enrich imported files with LLM-driven semantic analysis"
                   :usage "analyze [options] <repo-path>"}
   "postprocess"  {:spec postprocess-command-spec
                   :summary "Extract cross-file import graph deterministically"
                   :usage "postprocess [options] <repo-path>"
                   :epilog "Parses source code imports and resolves them to repo files.\nFull support: Clojure. Import extraction: Elixir, Python, JS/TS, C/C++, Go, Rust, Java, Erlang.\nOther languages are skipped. External tools (elixir, python3, node, etc.) required on PATH."}
   "sync"      {:spec sync-command-spec
                :summary "Sync knowledge graph with latest git state"
                :usage "sync [options] <repo-path>"
                :epilog "Detects changes since last sync via git HEAD SHA.\nFirst run: performs full import + postprocess.\nSubsequent runs: incrementally updates changed/added/deleted files.\nPass --analyze to also re-analyze changed files (requires LLM)."}
   "watch"     {:spec watch-command-spec
                :summary "Watch a repository and auto-sync on new commits"
                :usage "watch [options] <repo-path>"
                :epilog "Polls git HEAD every --interval seconds (default: 30).\nRuns sync automatically when new commits are detected.\nPass --analyze to also re-analyze changed files."}
   "query"     {:spec query-command-spec
                :summary "Run a named Datalog query against the knowledge graph"
                :usage "query [options] <query-name> <repo-path>"}
   "status"    {:spec simple-command-spec
                :summary "Show import counts for a repository"
                :usage "status [options] <repo-path>"}
   "databases" {:spec databases-command-spec
                :summary "List all databases or delete one"
                :usage "databases [--delete <name>] [options]"}
   "agent"     {:spec agent-command-spec
                :summary "Ask a question about a repository using AI-powered querying"
                :usage "agent -q <question> [options] <repo-path>"
                :epilog "Exit codes: 0 = answered, 1 = error, 2 = budget exhausted (no answer found)."}
   "benchmark" {:spec benchmark-command-spec
                :summary "Run benchmark suite against a repository"
                :usage "benchmark [options] <repo-path>"}
   "serve"     {:spec {:flags [{:flag "--db-dir" :key :db-dir :parse :string
                                :desc "Override storage directory (default: data/datomic/)"
                                :error-missing :missing-db-dir-value}
                               {:flag "--provider" :key :provider :parse :string
                                :desc "LLM provider for ask tool (default: glm)"
                                :valid all-valid-providers
                                :error-invalid :invalid-provider
                                :error-missing :missing-provider-value}
                               {:flag "--model" :key :model :parse :string
                                :desc "Model alias for ask tool"
                                :error-missing :missing-model-value}
                               {:flag "--no-auto-sync" :key :no-auto-sync :parse :bool
                                :desc "Disable automatic sync before queries (default: enabled)"}]
                       :initial {:subcommand "serve"}
                       :positionals {:required 0 :error nil :keys []}}
                :summary "Start MCP server (JSON-RPC over stdio)"
                :usage "serve [options]"}
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
  ["import" "analyze" "postprocess" "sync" "watch" "query" "status" "databases" "agent" "serve" "benchmark" "longbench"])

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
             [(str "Usage: " program-name " <subcommand> [options]")
              ""
              "Subcommands:"]
             (mapv (fn [cmd]
                     (format "  %-12s %s" cmd (:summary (command-registry cmd))))
                   command-order)
             [""
              "Universal flags:"
              (format "  %-24s %s" "-h, --help" "Show help (global or per-subcommand)")
              (format "  %-24s %s" "--version" "Print version and exit")
              ""
              (str "Run `" program-name " <subcommand> --help` for subcommand-specific options.")])))

(defn format-subcommand-help [subcommand]
  (when-let [{:keys [spec usage summary epilog]} (command-registry subcommand)]
    (if-let [subs (:subcommands spec)]
      ;; Nested subcommand (longbench)
      (str/join "\n"
                (concat
                 [(str summary)
                  ""
                  (str "Usage: " program-name " " usage)
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
                (cond-> [(str summary)
                         ""
                         (str "Usage: " program-name " " usage)
                         ""
                         "Options:"
                         (format-flags (:flags spec))]
                  epilog (conj "" epilog))))))

;; --- Core parser ---

(defn- flag-map [flags]
  (zipmap (map :flag flags) flags))

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
  (cond
    (contains-help? args)
    {:help "longbench"}

    (empty? args)
    {:error :longbench-no-subcommand :subcommand "longbench"}

    :else
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
  (cond
    (contains-help? args) {:help sub}
    (and (= "query" sub) (= "list" (first args)))
    {:subcommand "query" :list-queries true}
    :else
    (let [spec (case sub
                 "query"       query-command-spec
                 "analyze"     analyze-command-spec
                 "postprocess" postprocess-command-spec
                 "sync"        sync-command-spec
                 "watch"       watch-command-spec
                 simple-command-spec)
          result (parse-command spec args)]
      (assoc result :subcommand sub))))

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
          "serve"     (if (contains-help? rest-args)
                        {:help "serve"}
                        (let [result (parse-command (get-in command-registry ["serve" :spec]) rest-args)]
                          (if (:error result) result
                              (assoc result :subcommand "serve"))))
          "databases" (if (contains-help? rest-args)
                        {:help "databases"}
                        (let [result (parse-command databases-command-spec rest-args)]
                          (if (:error result) result
                              (assoc result :subcommand "databases"))))
          (if (#{"import" "status" "analyze" "postprocess" "query" "sync" "watch"} sub)
            (parse-simple-args sub rest-args)
            {:error :unknown-subcommand :subcommand sub}))))))
