(ns noum.cli
  "Command definitions, arg parsing, and help text for the noum launcher."
  (:require [clojure.string :as str]))

;; --- Command registry ---

(def commands
  "All noum commands. Each key is the command name (one word, no hyphens)."
  ;; Note: --host/--token/--insecure select remote backend; otherwise commands use local daemon.
  {"digest"     {:summary "Run full pipeline: import, enrich, analyze, benchmark"
                 :usage   "noum digest <repo> [options]"
                 :options ["--provider <glm|claude-api>  LLM provider"
                           "--model <alias>                          Model alias"
                           "--max-cost <dollars>                     Stop when session cost exceeds threshold"
                           "--db-dir <path>                          Override storage directory"
                           "--concurrency <1-20>                     Parallel workers"
                           "--min-delay <ms>                         Min delay between LLM requests"
                           "--max-questions <n>                      Stop after N questions"
                           "--stop-after <seconds>                   Stop after N seconds"
                           "--skip-import                            Skip import step"
                           "--skip-enrich                            Skip enrich step"
                           "--skip-analyze                           Skip analyze step"
                           "--skip-synthesize                        Skip synthesize step"
                           "--skip-benchmark                         Skip benchmark step"
                           "--path <file|dir>                        File/dir selector"
                           "--include <glob[,glob]>                  Include glob selector(s)"
                           "--exclude <glob[,glob]>                  Exclude glob selector(s)"
                           "--lang <lang[,lang]>                     Language selector(s)"
                           "--layers <raw,import,enrich,full>        Benchmark layers"
                           "--report                                 Generate benchmark report"]
                 :api-path "/api/digest" :api-method :post :min-args 1}
   "import"     {:summary "Import commit history and file structure"
                 :usage   "noum import <repo> [options]\n\n  <repo> can be a local path, Git URL, or Perforce depot path (//depot/...).\n  Perforce depots are cloned via git-p4 with automatic binary exclusions."
                 :options ["--db-dir <path>                          Override storage directory"
                           "--host <url>                             Use remote Noumenon host"
                           "--token <token>                          Auth token for remote host"
                           "--insecure                               Use HTTP for remote host"]
                 :api-path "/api/import" :api-method :post :min-args 1}
   "analyze"    {:summary "Run LLM semantic analysis on repository files"
                 :usage   "noum analyze <repo> [options]"
                 :options ["--provider <glm|claude-api>  LLM provider"
                           "--model <alias>                          Model alias (e.g. sonnet, haiku, opus)"
                           "--max-files <n>                          Stop after analyzing N files"
                           "--reanalyze <scope>                      Re-analyze scope: all|prompt-changed|model-changed|stale"
                           "--no-promote                             Skip the content-addressed cache; always call the LLM"
                           "--path <file|dir>                        File/dir selector"
                           "--include <glob[,glob]>                  Include glob selector(s)"
                           "--exclude <glob[,glob]>                  Exclude glob selector(s)"
                           "--lang <lang[,lang]>                     Language selector(s)"
                           "--concurrency <1-20>                     Parallel workers"
                           "--min-delay <ms>                         Min delay between LLM requests"
                           "--max-cost <dollars>                     Stop when session cost exceeds threshold"
                           "--db-dir <path>                          Override storage directory"
                           "--verbose, -v                            Log verbose output"]
                 :api-path "/api/analyze" :api-method :post :min-args 1}
   "enrich"     {:summary "Extract cross-file import graph deterministically"
                 :usage   "noum enrich <repo> [options]"
                 :options ["--db-dir <path>                          Override storage directory"
                           "--concurrency <1-20>                     Parallel workers"
                           "--path <file|dir>                        File/dir selector"
                           "--include <glob[,glob]>                  Include glob selector(s)"
                           "--exclude <glob[,glob]>                  Exclude glob selector(s)"
                           "--lang <lang[,lang]>                     Language selector(s)"
                           "--host <url>                             Use remote Noumenon host"
                           "--token <token>                          Auth token for remote host"
                           "--insecure                               Use HTTP for remote host"]
                 :api-path "/api/enrich" :api-method :post :min-args 1}
   "update"     {:summary "Sync knowledge graph with latest changes"
                 :usage   "noum update <repo> [options]\n\n  For git-p4 clones, automatically syncs from Perforce before updating."
                 :options ["--analyze                                Also run analysis on changed files"
                           "--provider <glm|claude-api>  LLM provider"
                           "--model <alias>                          Model alias"
                           "--db-dir <path>                          Override storage directory"
                           "--concurrency <1-20>                     Parallel workers"
                           "--path <file|dir>                        File/dir selector"
                           "--include <glob[,glob]>                  Include glob selector(s)"
                           "--exclude <glob[,glob]>                  Exclude glob selector(s)"
                           "--lang <lang[,lang]>                     Language selector(s)"
                           "--host <url>                             Use remote Noumenon host"
                           "--token <token>                          Auth token for remote host"
                           "--insecure                               Use HTTP for remote host"]
                 :api-path "/api/update" :api-method :post :min-args 1}
   "watch"      {:summary "Watch repository and auto-update on new commits"
                 :usage   "noum watch <repo> [--interval N]"
                 :options ["--interval <seconds>                     Poll interval (default: 30)"
                           "--analyze                                Also run analysis on changed files"
                           "--db-dir <path>                          Override storage directory"]
                 :min-args 1}
   "ask"        {:summary "Ask a question about a repository using AI"
                 :usage   "noum ask <repo> <question...> [options]"
                 :options ["--provider <glm|claude-api>  LLM provider"
                           "--model <alias>                          Model alias"
                           "--max-iterations <n>                     Max query iterations"
                           "--continue-from <session-id>             Resume budget-exhausted session"
                           "--no-auto-federate                       Skip auto-federation banner when on a feature branch"
                           "--db-dir <path>                          Override storage directory"
                           "--verbose, -v                            Verbose output"
                           "--host <url>                             Use remote Noumenon host"
                           "--token <token>                          Auth token for remote host"
                           "--insecure                               Use HTTP for remote host"]
                 :api-path "/api/ask" :api-method :post :min-args 2
                 :positional-map :ask}
   "query"      {:summary "Run a named or raw Datalog query"
                 :usage   "noum query <name> <repo> [--param key=value] [--as-of <date>]\n  noum query --raw '<datalog>' <repo> [--limit N]\n  noum query <name> <repo> --federate --basis-sha <sha>  (federated: trunk + local delta)"
                 :options ["--param <key=value>                      Query parameter (repeat command as needed)"
                           "--as-of <date>                           Time-travel query"
                           "--raw '<datalog>'                        Raw Datalog query text"
                           "--federate                               Federate against a local delta DB"
                           "--basis-sha <sha>                        Trunk basis SHA for federation (40-char hex)"
                           "--branch <name>                          Branch name override (defaults to current git branch)"
                           "--no-auto-federate                       Disable transparent auto-federation for this call"
                           "--limit <n>                              Max rows"
                           "--db-dir <path>                          Override storage directory"
                           "--host <url>                             Use remote Noumenon host"
                           "--token <token>                          Auth token for remote host"
                           "--insecure                               Use HTTP for remote host"]
                 :api-path "/api/query" :api-method :post :min-args 2
                 :positional-map :query}
   "queries"    {:summary "List available named queries"
                 :usage   "noum queries"
                 :api-path "/api/queries" :api-method :get}
   "schema"     {:summary "Show the database schema"
                 :usage   "noum schema <repo>"
                 :min-args 1}
   "status"     {:summary "Show entity counts for a repository"
                 :usage   "noum status <repo>"
                 :min-args 1}
   "bench"      {:summary "Run benchmark evaluation"
                 :usage   "noum bench <repo> [options]"
                 :options ["--provider <glm|claude-api>  LLM provider"
                           "--model <alias>                          Model alias"
                           "--judge-model <alias>                    Judge model alias"
                           "--concurrency <1-20>                     Parallel workers"
                           "--min-delay <ms>                         Min delay between LLM requests"
                           "--max-cost <dollars>                     Stop when cost exceeds threshold"
                           "--max-questions <n>                      Stop after N questions"
                           "--stop-after <seconds>                   Stop after N seconds"
                           "--resume [run-id]                        Resume from checkpoint (default: latest)"
                           "--layers <raw,import,enrich,full>        Layers to benchmark"
                           "--skip-raw                               Omit raw layer"
                           "--skip-judge                             Skip judge stages"
                           "--fast                                   Cheapest mode"
                           "--full                                   Include LLM-judged questions"
                           "--canary                                 Run canary first"
                           "--report                                 Generate benchmark report"
                           "--db-dir <path>                          Override storage directory"]
                 :api-path "/api/benchmark" :api-method :post :min-args 1}
   "results"    {:summary "Get benchmark results"
                 :usage   "noum results <repo> [--run-id <id>]"
                 :min-args 1}
   "compare"    {:summary "Compare two benchmark runs"
                 :usage   "noum compare <repo> <run-a> <run-b>"
                 :min-args 3}
   "introspect" {:summary "Run autonomous self-improvement loop"
                 :usage   "noum introspect <repo> [options]\n  noum introspect --status <run-id>\n  noum introspect --stop <run-id>\n  noum introspect --history [<query-name>]"
                 :options ["--provider <glm|claude-api>  LLM provider"
                           "--model <alias>                          Model alias"
                           "--max-cost <dollars>                     Stop when cost exceeds threshold"
                           "--max-iterations <n>                     Max improvement iterations"
                           "--max-hours <n>                          Max wall-clock hours"
                           "--target <csv>                           Targets: examples,system-prompt,rules,code,train"
                           "--eval-runs <n>                          Evaluation runs per iteration"
                           "--git-commit                             Commit after each improvement"
                           "--extra-repos <csv>                      Extra repos for evaluation"
                           "--status <run-id>                        Check run status"
                           "--stop <run-id>                          Stop run"
                           "--history [query-name]                   Show run history"
                           "--db-dir <path>                          Override storage directory"]
                 :api-path "/api/introspect" :api-method :post :min-args 1}
   "sessions"   {:summary "List or view past ask sessions"
                 :usage   "noum sessions [<session-id>]"}
   "feedback"   {:summary "Submit feedback on an ask session"
                 :usage   "noum feedback <session-id> positive|negative [--comment \"...\"]"
                 :options ["--comment <text>                         Optional feedback comment"]
                 :min-args 2}
   "databases"  {:summary "List all databases"
                 :usage   "noum databases"
                 :api-path "/api/databases" :api-method :get}
   "delete"     {:summary "Delete a database (prompts for confirmation)"
                 :usage   "noum delete <name> [--force]"
                 :options ["--force                                  Skip confirmation prompt"]
                 :min-args 1}
   "settings"   {:summary "View or update settings"
                 :usage   "noum settings [<key> [<value>]]"}
   "reseed"     {:summary "Reload prompts, queries, and rules"
                 :usage   "noum reseed"
                 :api-path "/api/reseed" :api-method :post}
   "history"    {:summary "Show artifact change history"
                 :usage   "noum history rules\n  noum history prompt <name>\n\n  Run `noum history prompt` to see available prompt names."}
   "synthesize" {:summary "Identify architectural components from analyzed data"
                 :usage   "noum synthesize <repo> [options]"
                 :api-path "/api/synthesize" :api-method :post :min-args 1}
   "delta-ensure" {:summary "Materialize a local delta DB against a trunk basis SHA"
                   :usage   "noum delta-ensure <repo> --basis-sha <sha> [--branch <name>]"
                   :options ["--basis-sha <sha>                        Trunk HEAD SHA to diff against (required)"
                             "--branch <name>                          Branch name (default: current)"
                             "--parent-host <host>                     Host of the trunk DB"
                             "--parent-db-name <name>                  Name of the trunk DB"
                             "--host <url>                             Use remote Noumenon host"
                             "--token <token>                          Auth token for remote host"
                             "--insecure                               Use HTTP for remote host"]
                   :api-path "/api/delta/ensure" :api-method :post :min-args 1}
   "embed"      {:summary "Build TF-IDF vector index for semantic search"
                 :usage   "noum embed <repo>"
                 :api-path "/api/embed" :api-method :post :min-args 1}
   "demo"       {:summary "Download pre-built demo database for instant querying"
                 :usage   "noum demo [--force]\n\n  Downloads a fully analyzed knowledge graph of the Noumenon repository.\n  No LLM credentials needed — try queries immediately:\n\n    noum ask noumenon \"Describe the architecture\"\n    noum query components noumenon\n    noum status noumenon"}
   "setup"      {:summary "Configure MCP for Claude Desktop or Code"
                 :usage   "noum setup <desktop|code>"}
   "serve"      {:summary "Start MCP server (stdin/stdout, for Claude Desktop/Code)"
                 :usage   "noum serve [options]"
                 :options ["--db-dir <path>                          Override storage directory"
                           "--provider <glm|claude-api>  Default LLM provider"
                           "--model <alias>                          Default model alias"
                           "--token <token>                          Set NOUMENON_TOKEN env var"]}
   "start"      {:summary "Start the daemon"
                 :usage   "noum start [options]"
                 :options ["--db-dir <path>                          Override storage directory"
                           "--provider <glm|claude-api>  Default LLM provider"
                           "--model <alias>                          Default model alias"
                           "--token <token>                          Set bearer token"
                           "--host <url>                             Connect to remote host instead"
                           "--insecure                               Use HTTP for remote host"]}
   "stop"       {:summary "Stop the daemon"
                 :usage   "noum stop"}
   "ping"       {:summary "Check daemon health"
                 :usage   "noum ping"}
   "upgrade"    {:summary "Update noumenon.jar (re-run installer to update noum)"
                 :usage   "noum upgrade"}
   "open"       {:summary "Open the visual UI"
                 :usage   "noum open"}
   "help"       {:summary "Show help"
                 :usage   "noum help [command]"}
   "version"    {:summary "Show version"
                 :usage   "noum version"}
   "connect"    {:summary "Connect to a remote Noumenon instance"
                 :usage   "noum connect <url> --token <token>\n       noum connect local"
                 :options ["--token <token>                          Required for remote connection"
                           "--name <name>                            Save connection under this name"
                           "--insecure                               Use HTTP (default is HTTPS)"]}
   "connections" {:summary "List configured connections"
                  :usage   "noum connections"}
   "disconnect" {:summary "Remove a saved connection"
                 :usage   "noum disconnect <name>"}})

(def command-groups
  [["Get Started" ["demo" "setup"]]
   ["Pipeline"   ["digest" "import" "analyze" "enrich" "synthesize" "embed" "update" "watch" "delta-ensure"]]
   ["Query"      ["ask" "query" "queries" "sessions" "feedback" "schema" "status"]]
   ["Benchmark"  ["bench" "results" "compare"]]
   ["Introspect" ["introspect"]]
   ["Admin"      ["databases" "delete" "reseed" "history" "settings"]]
   ["Server"     ["serve"]]
   ["Remote"     ["connect" "connections" "disconnect"]]
   ["Daemon"     ["start" "stop" "ping" "upgrade"]]
   ["UI"         ["open"]]
   ["Other"      ["help" "version"]]])

(defn format-help
  "Format global help text."
  []
  (str "Usage: noum <command> [options]\n\n"
       (str/join "\n\n"
                 (map (fn [[group cmds]]
                        (str group ":\n"
                             (str/join "\n"
                                       (map (fn [cmd]
                                              (format "  %-14s %s" cmd
                                                      (:summary (commands cmd) "")))
                                            cmds))))
                      command-groups))
       "\n\nRun `noum help <command>` for command-specific help."))

(defn format-command-help
  "Format help for a specific command."
  [cmd-name]
  (when-let [cmd (commands cmd-name)]
    (str (:summary cmd) "\n\n"
         "Usage: " (:usage cmd)
         (when-let [opts (:options cmd)]
           (str "\n\nOptions:\n"
                (str/join "\n" (map #(str "  " %) opts))))
         "\n")))

;; --- Arg parsing ---

(def ^:private boolean-flags
  "Flags that never take a value — always treated as true when present."
  #{"--skip-import" "--skip-enrich" "--skip-analyze" "--skip-synthesize" "--skip-benchmark"
    "--report" "--force" "--analyze" "--verbose" "--debug" "--canary"
    "--deterministic-only" "--git-commit" "--read-only" "--federate" "--no-promote"
    "--no-auto-federate"})

(def ^:private repeatable-flags
  "Flags whose values accumulate into a vector instead of overwriting.
   The corresponding flag value is always a vector of strings, even
   when only one occurrence was passed."
  #{:param})

(defn- assoc-flag
  "Set or accumulate a flag value into the flags map. Repeatable flags
   collect into a vector; everything else overwrites prior values."
  [flags k v]
  (if (repeatable-flags k)
    (update flags k (fnil conj []) v)
    (assoc flags k v)))

(defn- extract-flags
  "Extract --flag value pairs from args. Returns [flags-map remaining-args]."
  [args]
  (loop [remaining args, flags {}, positional []]
    (if-not (seq remaining)
      [flags positional]
      (let [[arg & more] remaining]
        (cond
          (= "--" arg)
          [flags (into positional more)]

          (str/starts-with? arg "--")
          (let [key (keyword (subs arg 2))]
            (if (or (boolean-flags arg)
                    (empty? more)
                    (str/starts-with? (first more) "--"))
              (recur more (assoc-flag flags key true) positional)
              (recur (rest more) (assoc-flag flags key (first more)) positional)))

          (= "-v" arg)
          (recur more (assoc flags :verbose true) positional)

          :else
          (recur more flags (conj positional arg)))))))

(defn- sanitize-positional
  "Strip NUL bytes from a positional arg and treat blank/whitespace-only
   results as missing. NUL bytes don't survive any serializer cleanly
   and only appear from copy-paste / programmatic mistakes; blanks
   silently resolve to the cwd via `path->db-name`, which causes the
   wrong DB to be addressed. A literal `.` survives — it's a legitimate
   current-directory shorthand."
  [s]
  (let [stripped (str/replace s " " "")]
    (when-not (str/blank? stripped)
      stripped)))

(defn parse-args
  "Parse CLI args. Returns {:command 'name' :flags {} :positional [] :error nil}."
  [args]
  (if-not (seq args)
    {:error :no-args}
    (let [[cmd & rest-args] args
          [flags positional] (extract-flags rest-args)
          positional         (into [] (keep sanitize-positional) positional)]
      (cond
        (= "--version" cmd) {:command "version"}
        (#{"--help" "-h"} cmd) {:command "help"}
        (commands cmd) {:command cmd :flags flags :positional positional}
        :else {:error :unknown-command :command cmd}))))
