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
    :desc "Parallel workers, 1-20 (default varies: analyze=3, others=8)"
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
   :desc "Resume from checkpoint (default: latest). Place before <repo-path> to avoid ambiguity. The next non-flag argument is consumed as the run ID."})

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
         {:flag "--max-files" :key :max-files :parse :pos-int
          :desc "Stop after analyzing N files (useful for sampling)"
          :error-invalid :invalid-max-files :error-missing :missing-max-files-value}
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

(def ^:private enrich-command-spec
  {:flags [db-dir-flag
           {:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
            :desc "Parallel workers, 1-20 (default: 8)"
            :error-invalid :invalid-concurrency :error-missing :missing-concurrency-value}]
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private update-command-spec
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

(def ^:private list-databases-command-spec
  {:flags [db-dir-flag
           {:flag "--delete" :key :delete :parse :string
            :desc "Delete a database by name"
            :error-missing :missing-delete-value}]
   :initial {:subcommand "list-databases"}
   :positionals {:required 0 :error nil :keys []}})

(def ^:private analyze-command-spec
  {:flags (with-provider-valid analyze-flags all-valid-providers)
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private query-command-spec
  {:flags [db-dir-flag
           {:flag "--param" :key :params :parse :kv-pair
            :desc "Supply query input as key=value (repeatable)"
            :error-missing :missing-param-value
            :error-invalid :invalid-param-value}]
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
                        {:flag "--layers" :key :layers :parse :string
                         :desc "Comma-separated layers to test: raw,import,enrich,full (default: raw,full)"
                         :error-missing :missing-layers-value}
                        {:flag "--skip-raw" :key :skip-raw :parse :bool
                         :desc "Omit raw-context condition (shorthand for --layers full)"}
                        {:flag "--skip-judge" :key :skip-judge :parse :bool
                         :desc "Skip LLM judge stages; use deterministic scoring where available"}
                        {:flag "--fast" :key :fast :parse :bool
                         :desc "Deterministic questions only, full layer only (cheapest mode)"}
                        {:flag "--full" :key :full :parse :bool
                         :desc "Run all questions including LLM-judged (default runs deterministic only)"}
                        {:flag "--canary" :key :canary :parse :bool
                         :desc "Run q01+q02 first as canary; warn if both fail"}
                        {:flag "--report" :key :report :parse :bool
                         :desc "Generate Markdown report alongside EDN checkpoint"}]))
   :initial {:subcommand "benchmark"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private ask-command-spec
  {:flags [{:flag "-q" :key :question :parse :string
            :desc "Alias for --question"
            :error-missing :ask-missing-question}
           {:flag "--question" :key :question :parse :string
            :desc "Question to ask about the repository"
            :error-missing :ask-missing-question}
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
   :initial {:subcommand "ask"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

;; --- Command registry (drives help generation) ---

(def command-registry
  {"import"    {:spec simple-command-spec
                :summary "Import git history and file structure into Datomic"
                :usage "import [options] <repo-path-or-url>"
                :epilog "Accepts a local path or a Git URL (https://, git@).\nURLs are auto-cloned to data/repos/<name>/."}
   "analyze"      {:spec analyze-command-spec
                   :summary "Enrich imported files with LLM-driven semantic analysis"
                   :usage "analyze [options] <repo-path>"
                   :epilog "Sensitive files (.env, *.pem, credentials, SSH keys, etc.) are\nautomatically excluded — their contents are never sent to the LLM."}
   "enrich"       {:spec enrich-command-spec
                   :summary "Extract cross-file import graph deterministically"
                   :usage "enrich [options] <repo-path>"
                   :epilog "Parses source code imports and resolves them to repo files.\nFull support: Clojure. Import extraction: Elixir, Python, JS/TS, C/C++, Go, Rust, Java, Erlang.\nOther languages are skipped. External tools (elixir, python3, node, etc.) required on PATH."}
   "update"    {:spec update-command-spec
                :summary "Update knowledge graph with latest git state"
                :usage "update [options] <repo-path>"
                :epilog "Detects changes since last update via git HEAD SHA.\nFirst run: performs full import + enrich.\nSubsequent runs: incrementally updates changed/added/deleted files.\nPass --analyze to also re-analyze changed files (requires LLM)."}
   "watch"     {:spec watch-command-spec
                :summary "Watch a repository and auto-update on new commits"
                :usage "watch [options] <repo-path>"
                :epilog "Polls git HEAD every --interval seconds (default: 30).\nRuns update automatically when new commits are detected.\nPass --analyze to also re-analyze changed files."}
   "query"     {:spec query-command-spec
                :summary "Run a named Datalog query against the knowledge graph"
                :usage "query [options] <query-name> <repo-path>\n       query list"}
   "status"         {:spec simple-command-spec
                     :summary "Show import counts for a repository"
                     :usage "status [options] <repo-path>"}
   "show-schema"    {:spec simple-command-spec
                     :summary "Show the database schema with all attributes and types"
                     :usage "show-schema [options] <repo-path>"}
   "list-databases" {:spec list-databases-command-spec
                     :summary "List all databases or delete one"
                     :usage "list-databases [--delete <name>] [options]"}
   "ask"            {:spec ask-command-spec
                     :summary "Ask a question about a repository using AI-powered querying"
                     :usage "ask -q <question> [options] <repo-path>"
                     :epilog "Exit codes: 0 = answered, 1 = error, 2 = budget exhausted (no answer found)."}
   "benchmark" {:spec benchmark-command-spec
                :summary "Evaluate knowledge graph efficacy against a repository"
                :usage "benchmark [options] <repo-path>"
                :epilog "By default, runs 22 deterministic questions (objective, reproducible).\nPass --full to include 18 LLM-judged architectural questions.\nUse --fast for cheapest mode (deterministic + query-only, no raw context)."}
   "serve"     {:spec {:flags [{:flag "--db-dir" :key :db-dir :parse :string
                                :desc "Override storage directory (default: data/datomic/)"
                                :error-missing :missing-db-dir-value}
                               {:flag "--provider" :key :provider :parse :string
                                :desc "Default LLM provider (default: glm)"
                                :valid all-valid-providers
                                :error-invalid :invalid-provider
                                :error-missing :missing-provider-value}
                               {:flag "--model" :key :model :parse :string
                                :desc "Default model alias"
                                :error-missing :missing-model-value}
                               {:flag "--no-auto-update" :key :no-auto-update :parse :bool
                                :desc "Disable automatic update before queries (default: enabled)"}]
                       :initial {:subcommand "serve"}
                       :positionals {:required 0 :error nil :keys []}}
                :summary "Start MCP server (JSON-RPC over stdio)"
                :usage "serve [options]"}})

(def ^:private command-order
  ["import" "analyze" "enrich" "update" "watch" "query" "show-schema" "status" "list-databases" "ask" "serve" "benchmark"])

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
                     (format "  %-16s %s" cmd (:summary (command-registry cmd))))
                   command-order)
             [""
              "Universal flags:"
              (format "  %-24s %s" "-h, --help" "Show help (global or per-subcommand)")
              (format "  %-24s %s" "--version" "Print version and exit")
              ""
              (str "Run `" program-name " <subcommand> --help` for subcommand-specific options.")])))

(defn format-subcommand-help [subcommand]
  (when-let [{:keys [spec usage summary epilog]} (command-registry subcommand)]
    (str/join "\n"
              (cond-> [(str summary)
                       ""
                       (str "Usage: " program-name " " usage)
                       ""
                       "Options:"
                       (format-flags (:flags spec))]
                epilog (conj "" epilog)))))

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

              :kv-pair
              (let [raw (first more)]
                (if-not raw
                  {:error (:error-missing spec)}
                  (let [eq-idx (str/index-of raw "=")]
                    (if (or (nil? eq-idx) (zero? eq-idx))
                      {:error (:error-invalid spec) :value raw}
                      (let [k (subs raw 0 eq-idx)
                            v (subs raw (inc eq-idx))]
                        (recur (rest more)
                               (update opts (:key spec) assoc k v)
                               positional))))))

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
    (let [result (parse-command benchmark-command-spec args)
          ;; Parse --layers "raw,full" into keyword vector
          result (if-let [layers-str (:layers result)]
                   (assoc result :layers (mapv keyword (str/split layers-str #",")))
                   result)]
      (cond
        (:error result) result
        ;; --fast: deterministic only, full layer only (cheapest mode)
        (:fast result)  (-> result
                            (assoc :skip-judge true :deterministic-only true
                                   :layers [:full])
                            (dissoc :fast :full))
        ;; --full: all questions, default layers (most expensive)
        (:full result)  (dissoc result :full)
        ;; Default: deterministic only, default layers
        :else           (assoc result :deterministic-only true)))))

(defn- validate-ask-question
  "Ensure ask opts include :question. Returns opts or {:error ...}."
  [opts]
  (if (:question opts)
    opts
    {:error :ask-missing-question}))

(defn parse-ask-args [args]
  (if (contains-help? args)
    {:help "ask"}
    (let [result (parse-command ask-command-spec args)]
      (if (:error result)
        result
        (validate-ask-question result)))))

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
                 "enrich"      enrich-command-spec
                 "update"      update-command-spec
                 "watch"       watch-command-spec
                 simple-command-spec)
          result (parse-command spec args)]
      (cond-> (assoc result :subcommand sub)
        (:error result) (select-keys [:error :subcommand :flag :value])))))

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
          "benchmark"      (parse-benchmark-args rest-args)
          "ask"            (parse-ask-args rest-args)
          "serve"          (if (contains-help? rest-args)
                             {:help "serve"}
                             (let [result (parse-command (get-in command-registry ["serve" :spec]) rest-args)]
                               (if (:error result) result
                                   (assoc result :subcommand "serve"))))
          "list-databases" (if (contains-help? rest-args)
                             {:help "list-databases"}
                             (let [result (parse-command list-databases-command-spec rest-args)]
                               (if (:error result) result
                                   (assoc result :subcommand "list-databases"))))
          (if (#{"import" "status" "show-schema" "analyze" "enrich" "query" "update" "watch"} sub)
            (parse-simple-args sub rest-args)
            {:error :unknown-subcommand :subcommand sub}))))))
