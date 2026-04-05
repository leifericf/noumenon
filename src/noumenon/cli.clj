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

;; --- Reusable flag atoms ---

(def ^:private db-dir-flag
  {:flag "--db-dir" :key :db-dir :parse :string
   :desc "Override storage directory (default: data/datomic/)"
   :error-missing :missing-db-dir-value})

(def ^:private model-flag
  {:flag "--model" :key :model :parse :string
   :desc "Model alias (e.g. sonnet, haiku, opus)"
   :error-missing :missing-model-value})

(def ^:private provider-flag
  {:flag "--provider" :key :provider :parse :string
   :desc "Provider: glm (default), claude-api, claude-cli (alias: claude)"
   :error-invalid :invalid-provider :error-missing :missing-provider-value})

(def ^:private verbose-flags
  [{:flag "--verbose" :key :verbose :parse :bool
    :desc "Log verbose output to stderr"}
   {:flag "-v" :key :verbose :parse :bool}])

(def ^:private concurrency-flag
  {:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
   :desc "Parallel workers, 1-20 (default varies: analyze=3, others=8)"
   :error-invalid :invalid-concurrency :error-missing :missing-concurrency-value})

(def ^:private max-cost-flag
  {:flag "--max-cost" :key :max-cost :parse :pos-double
   :desc "Stop when session cost exceeds threshold (dollars)"
   :error-invalid :invalid-max-cost :error-missing :missing-max-cost-value})

(def ^:private max-files-flag
  {:flag "--max-files" :key :max-files :parse :pos-int
   :desc "Stop after analyzing N files (useful for sampling)"
   :error-invalid :invalid-max-files :error-missing :missing-max-files-value})

;; --- Composed flag sets ---

(def ^:private common-flags
  (vec (concat [model-flag provider-flag max-cost-flag db-dir-flag]
               verbose-flags)))

(def ^:private concurrency-flags
  [concurrency-flag
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

(def ^:private reanalyze-flag
  {:flag "--reanalyze" :key :reanalyze :parse :string
   :desc "Re-analyze files: all, prompt-changed, model-changed, stale (default: only unanalyzed files)"
   :error-missing :missing-reanalyze-value})

(def ^:private analyze-flags
  (vec (concat [model-flag (assoc provider-flag :valid all-valid-providers)
                max-files-flag reanalyze-flag db-dir-flag]
               verbose-flags concurrency-flags)))

;; --- Declarative command specs ---

(def ^:private simple-command-spec
  {:flags [db-dir-flag]
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private enrich-command-spec
  {:flags [db-dir-flag concurrency-flag]
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private update-command-spec
  {:flags [{:flag "--analyze" :key :analyze :parse :bool
            :desc "Also run LLM analysis on changed files"}
           model-flag
           (assoc provider-flag :valid all-valid-providers)
           db-dir-flag concurrency-flag]
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private watch-command-spec
  {:flags [{:flag "--interval" :key :interval :parse :pos-int
            :desc "Polling interval in seconds (default: 30)"
            :error-invalid :invalid-interval :error-missing :missing-interval-value}
           {:flag "--analyze" :key :analyze :parse :bool
            :desc "Also run LLM analysis on changed files"}
           model-flag
           (assoc provider-flag :valid all-valid-providers)
           db-dir-flag
           {:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
            :desc "Parallel workers for import/enrich, 1-20 (default: 8)"
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

(def ^:private synthesize-command-spec
  {:flags [model-flag
           (assoc provider-flag :valid all-valid-providers)
           db-dir-flag]
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
  {:flags (vec (concat
                [{:flag "-q" :key :question :parse :string
                  :desc "Question to ask (place before <repo-path>)"
                  :error-missing :ask-missing-question}
                 {:flag "--question" :key :question :parse :string
                  :desc "Question to ask about the repository (place before <repo-path>)"
                  :error-missing :ask-missing-question}
                 model-flag
                 (assoc provider-flag :valid all-valid-providers)
                 {:flag "--max-iterations" :key :max-iterations :parse :pos-int
                  :desc "Max query iterations (default: 10)"
                  :error-invalid :invalid-max-iterations
                  :error-missing :missing-max-iterations-value}
                 {:flag "--continue-from" :key :continue-from :parse :string
                  :desc "Session ID from a budget-exhausted run — resumes the agent (place before <repo-path>)"}
                 db-dir-flag]
                verbose-flags))
   :initial {:subcommand "ask"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private introspect-command-spec
  {:flags (vec (concat
                [model-flag
                 (assoc provider-flag :valid all-valid-providers)
                 max-cost-flag
                 db-dir-flag
                 {:flag "--max-iterations" :key :max-iterations :parse :pos-int
                  :desc "Max improvement iterations (default: 10)"
                  :error-invalid :invalid-max-iterations
                  :error-missing :missing-max-iterations-value}
                 {:flag "--max-hours" :key :max-hours :parse :pos-double
                  :desc "Stop after N hours of wall-clock time"
                  :error-invalid :invalid-max-hours
                  :error-missing :missing-max-hours-value}
                 {:flag "--target" :key :target :parse :string
                  :desc "Comma-separated targets: examples, system-prompt, rules, code, train (default: all — LLM chooses)"
                  :error-missing :missing-target-value}
                 {:flag "--eval-runs" :key :eval-runs :parse :pos-int
                  :desc "Evaluation passes per iteration for median (default: 1)"
                  :error-invalid :invalid-eval-runs
                  :error-missing :missing-eval-runs-value}
                 {:flag "--git-commit" :key :git-commit :parse :bool
                  :desc "Git commit after each improvement"}
                 {:flag "--extra-repos" :key :extra-repos :parse :string
                  :desc "Comma-separated extra repo paths/names for multi-repo evaluation"
                  :error-missing :missing-extra-repos-value}]
                verbose-flags))
   :initial {:subcommand "introspect"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

;; --- Command registry (drives help generation) ---

(def command-registry
  {"import"    {:spec simple-command-spec
                :summary "Import commit history and file structure into Datomic"
                :usage "import [options] <repo-path-or-url-or-depot>"
                :epilog "Accepts a local path, Git URL (https://, git@), or Perforce depot path\n(//depot/...). URLs and depot paths are auto-cloned to data/repos/<name>/.\nPerforce depots are cloned via git-p4 with default binary exclusions.\nStdout: EDN result map (for scripting). Human output goes to stderr."}
   "analyze"      {:spec analyze-command-spec
                   :summary "Enrich imported files with LLM-driven semantic analysis"
                   :usage "analyze [options] <repo-path>"
                   :epilog "Sensitive files (.env, *.pem, credentials, SSH keys, etc.) are\nautomatically excluded — their contents are never sent to the LLM.\n\nRe-analysis scopes (--reanalyze):\n  all              Re-analyze every file\n  prompt-changed   Files analyzed with a different prompt template\n  model-changed    Files analyzed with a different model\n  stale            Files modified by commits since their last analysis"}
   "enrich"       {:spec enrich-command-spec
                   :summary "Extract cross-file import graph deterministically"
                   :usage "enrich [options] <repo-path>"
                   :epilog "Parses source code imports and resolves them to repo files.\nFull support: Clojure. Import extraction: Elixir, Python, JS/TS, C/C++, Go, Rust, Java, Erlang.\nOther languages are skipped. External tools (elixir, python3, node, etc.) required on PATH.\nStdout: EDN result map (for scripting). Human output goes to stderr."}
   "embed"        {:spec simple-command-spec
                   :summary "Build TF-IDF vector index for semantic search"
                   :usage "embed [options] <repo-path>"
                   :epilog "Builds a TF-IDF vocabulary and vector index from file and component\nsummaries (requires prior analyze). Used by ask for semantic seeding\nand by the noumenon_search MCP tool.\nIndex is saved as a Nippy file alongside the Datomic database.\nIdempotent — rebuilds the full index on each run (~10ms for 300 files)."}
   "synthesize"   {:spec synthesize-command-spec
                   :summary "Identify architectural components from analyzed codebase data"
                   :usage "synthesize [options] <repo-path>"
                   :epilog "Queries the knowledge graph for file summaries, import edges, and\ndirectory structure, then uses an LLM to identify logical components,\nclassify files architecturally (layer, category, patterns, purpose),\nand map component dependencies.\n\nRun after analyze — requires sem/summary on files.\nIdempotent: re-running retracts and recreates components.\nStdout: EDN result map (for scripting). Human output goes to stderr."}
   "update"    {:spec update-command-spec
                :summary "Update knowledge graph with latest changes"
                :usage "update [options] <repo-path>"
                :epilog "Detects changes since last update via HEAD revision.\nFor git-p4 clones, automatically syncs from Perforce first.\nFirst run: performs full import + enrich.\nSubsequent runs: incrementally updates changed/added/deleted files.\nPass --analyze to also re-analyze changed files (requires LLM).\nStdout: EDN result map (for scripting). Human output goes to stderr."}
   "watch"     {:spec watch-command-spec
                :summary "Watch a repository and auto-update on new commits"
                :usage "watch [options] <repo-path>"
                :epilog "Polls git HEAD every --interval seconds (default: 30).\nRuns update automatically when new commits are detected.\nPass --analyze to also re-analyze changed files."}
   "query"     {:spec query-command-spec
                :summary "Run a named Datalog query against the knowledge graph"
                :usage "query [options] <query-name> <repo-path>\n       query list"
                :epilog "Run `query list` to see all available named queries with descriptions.\nSome queries accept --param key=value inputs — check the query listing.\nStdout: EDN result map (for scripting). Human output goes to stderr."}
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
                     :epilog "Exit codes: 0 = answered, 1 = error, 2 = budget exhausted (no answer found).\nStdout: EDN map with :answer, :status, and :usage (for scripting). Human output goes to stderr."}
   "benchmark" {:spec benchmark-command-spec
                :summary "Evaluate knowledge graph efficacy against a repository"
                :usage "benchmark [options] <repo-path>"
                :epilog "By default, runs 22 deterministic questions (objective, reproducible).\nPass --full to include 18 LLM-judged architectural questions.\nUse --fast for cheapest mode (deterministic + full-only, no raw context)."}
   "digest"    {:spec {:flags (vec (concat
                                    (with-provider-valid benchmark-flags all-valid-providers)
                                    concurrency-flags
                                    budget-flags
                                    [{:flag "--skip-import" :key :skip-import :parse :bool
                                      :desc "Skip import (use both --skip-import --skip-enrich to skip combined step)"}
                                     {:flag "--skip-enrich" :key :skip-enrich :parse :bool
                                      :desc "Skip enrich (use both --skip-import --skip-enrich to skip combined step)"}
                                     {:flag "--skip-analyze" :key :skip-analyze :parse :bool
                                      :desc "Skip analyze step"}
                                     {:flag "--skip-synthesize" :key :skip-synthesize :parse :bool
                                      :desc "Skip synthesize step (component identification)"}
                                     {:flag "--skip-benchmark" :key :skip-benchmark :parse :bool
                                      :desc "Skip benchmark step"}
                                     {:flag "--layers" :key :layers :parse :string
                                      :desc "Benchmark layers: raw,import,enrich,full (default: raw,full)"
                                      :error-missing :missing-layers-value}
                                     {:flag "--report" :key :report :parse :bool
                                      :desc "Generate Markdown benchmark report"}]))
                       :initial {:subcommand "digest"}
                       :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}}
                :summary "Run full pipeline: import, enrich, analyze, synthesize, benchmark"
                :usage "digest [options] <repo-path>"
                :epilog "Runs the entire Noumenon pipeline in one go. Each step is idempotent.\nUse --skip-* flags to omit individual steps.\nRe-running digest picks up where a previous run left off."}
   "introspect" {:spec introspect-command-spec
                 :summary "Autonomous self-improvement loop (optimize prompts via benchmark)"
                 :usage "introspect [options] <repo-path>"
                 :epilog "Runs an autonomous loop: propose prompt change, evaluate via benchmark,\nkeep if improved, revert if not. Uses an LLM to propose improvements and\nthe agent benchmark to evaluate them.\n\nTargets (comma-separated): examples, system-prompt, rules, code, train\n(default: all — LLM chooses based on benchmark results).\nThe :code target requires passing lint and compilation. The :train target retrains\nthe on-device ML model. Example: --target examples,system-prompt\nUse --max-hours or --max-cost for overnight runs."}
   "reseed"    {:spec {:flags [db-dir-flag]
                       :initial {:subcommand "reseed"}
                       :positionals {:required 0 :error nil :keys []}}
                :summary "Reseed prompts, queries, and rules into the meta database"
                :usage "reseed [options]"
                :epilog "Reloads all artifact definitions (prompts, queries, rules) from\nclasspath resources into the meta database. Safe to re-run."}
   "artifact-history"
   {:spec {:flags [{:flag "--type" :key :artifact-type :parse :string
                    :desc "Artifact type: prompt or rules (required)"
                    :error-missing :missing-artifact-type-value}
                   {:flag "--name" :key :artifact-name :parse :string
                    :desc "Artifact name (required for type=prompt)"
                    :error-missing :missing-artifact-name-value}
                   db-dir-flag]
           :initial {:subcommand "artifact-history"}
           :positionals {:required 0 :error nil :keys []}}
    :summary "Show change history for a prompt or rules artifact"
    :usage "artifact-history --type <prompt|rules> [--name <name>] [options]"
    :epilog "Shows the transact-time history of an artifact in the meta database.\nFor prompts, --name is required (e.g., --name analyze-file).\nFor rules, --name is not needed."}
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
                :usage "serve [options]"
                :epilog "Communicates via JSON-RPC over stdio (stdin/stdout).\nConfigure your MCP client to launch: noumenon serve [options]\nThe server auto-updates the knowledge graph on each query by default;\npass --no-auto-update to disable."}
   "daemon"    {:spec {:flags [{:flag "--db-dir" :key :db-dir :parse :string
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
                               {:flag "--port" :key :port :parse :range-int :min 0 :max 65535
                                :desc "HTTP port (default: 0 = auto-assign)"
                                :error-invalid :invalid-port :error-missing :missing-port-value}
                               {:flag "--bind" :key :bind :parse :string
                                :desc "Bind address (default: 127.0.0.1, use 0.0.0.0 for Docker)"
                                :error-missing :missing-bind-value}
                               {:flag "--token" :key :token :parse :string
                                :desc "Bearer token for authentication"
                                :error-missing :missing-token-value}]
                       :initial {:subcommand "daemon"}
                       :positionals {:required 0 :error nil :keys []}}
                :summary "Start HTTP daemon for noum CLI and future UI"
                :usage "daemon [options]"
                :epilog "Starts an HTTP API server on 127.0.0.1 for the noum launcher\nand future Electron UI. Writes connection info to ~/.noumenon/daemon.edn.\nUse --port to specify a fixed port, or omit for auto-assignment.\nUse --token for remote access authentication."}})

(def ^:private command-order
  ["digest" "import" "analyze" "enrich" "synthesize" "embed" "update" "watch" "query" "show-schema" "status" "list-databases" "ask" "serve" "daemon" "benchmark" "introspect" "reseed" "artifact-history"])

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

(defn- parse-one-flag
  "Parse a single flag from args. Returns {:opts updated-opts :remaining rest-args}
   or {:error kw ...} on failure."
  [spec opts more]
  (case (:parse spec)
    :bool
    {:opts (assoc opts (:key spec) true) :remaining more}

    :optional-string
    (let [next-arg (first more)]
      (if (or (nil? next-arg) (str/starts-with? next-arg "-"))
        {:opts (assoc opts (:key spec) "latest") :remaining more}
        {:opts (assoc opts (:key spec) next-arg) :remaining (rest more)}))

    :kv-pair
    (let [raw (first more)]
      (if-not raw
        {:error (:error-missing spec)}
        (let [eq-idx (str/index-of raw "=")]
          (if (or (nil? eq-idx) (zero? eq-idx))
            {:error (:error-invalid spec) :value raw}
            {:opts      (update opts (:key spec) assoc
                                (subs raw 0 eq-idx) (subs raw (inc eq-idx)))
             :remaining (rest more)}))))

    ;; default: typed value
    (let [raw (first more)]
      (if-not raw
        {:error (:error-missing spec)}
        (let [{:keys [ok error value]} (parse-value spec raw)]
          (cond
            error                                       {:error error :value value}
            (and (:valid spec) (not ((:valid spec) raw))) {:error (:error-invalid spec) :value raw}
            :else {:opts (assoc opts (:key spec) ok) :remaining (rest more)}))))))

(defn- parse-flags
  "Parse all flags in args. Returns [opts positional] or {:error ...}."
  [flags args initial]
  (let [lookup (flag-map flags)]
    (loop [remaining args, opts initial, positional []]
      (if-not (seq remaining)
        [opts positional]
        (let [[arg & more] remaining]
          (if-let [spec (lookup arg)]
            (let [result (parse-one-flag spec opts more)]
              (if (:error result)
                result
                (recur (:remaining result) (:opts result) positional)))
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
        (and (= :no-repo-path (:error result))
             (:resume result)
             (str/starts-with? (str (:resume result)) "/"))
        (assoc result :error :resume-consumed-repo-path)

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
          "digest"         (if (contains-help? rest-args)
                             {:help "digest"}
                             (let [result (parse-command (get-in command-registry ["digest" :spec]) rest-args)
                                   result (if-let [ls (:layers result)]
                                            (assoc result :layers (mapv keyword (str/split ls #",")))
                                            result)]
                               (if (:error result) result
                                   (assoc result :subcommand "digest"))))
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
          "introspect"     (if (contains-help? rest-args)
                             {:help "introspect"}
                             (let [result (parse-command introspect-command-spec rest-args)]
                               (cond-> (assoc result :subcommand "introspect")
                                 (:error result) (select-keys [:error :subcommand :flag :value]))))
          (if (#{"import" "status" "show-schema" "analyze" "enrich" "query" "update" "watch"} sub)
            (parse-simple-args sub rest-args)
            (if-let [spec (get-in command-registry [sub :spec])]
              (if (contains-help? rest-args)
                {:help sub}
                (let [result (parse-command spec rest-args)]
                  (if (:error result) result
                      (assoc result :subcommand sub))))
              {:error :unknown-subcommand :subcommand sub})))))))
