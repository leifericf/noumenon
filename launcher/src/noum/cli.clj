(ns noum.cli
  "Command definitions, arg parsing, and help text for the noum launcher."
  (:require [clojure.string :as str]))

;; --- Command registry ---

(def commands
  "All noum commands. Each key is the command name (one word, no hyphens)."
  {"digest"     {:summary "Run full pipeline: import, enrich, analyze, benchmark"
                 :usage   "noum digest <repo> [options]"
                 :api-path "/api/digest" :api-method :post :min-args 1}
   "import"     {:summary "Import commit history and file structure"
                 :usage   "noum import <repo> [options]\n\n  <repo> can be a local path, Git URL, or Perforce depot path (//depot/...).\n  Perforce depots are cloned via git-p4 with automatic binary exclusions."
                 :api-path "/api/import" :api-method :post :min-args 1}
   "analyze"    {:summary "Run LLM semantic analysis on repository files"
                 :usage   "noum analyze <repo> [options]"
                 :api-path "/api/analyze" :api-method :post :min-args 1}
   "enrich"     {:summary "Extract cross-file import graph deterministically"
                 :usage   "noum enrich <repo> [options]"
                 :api-path "/api/enrich" :api-method :post :min-args 1}
   "update"     {:summary "Sync knowledge graph with latest changes"
                 :usage   "noum update <repo> [options]\n\n  For git-p4 clones, automatically syncs from Perforce before updating."
                 :api-path "/api/update" :api-method :post :min-args 1}
   "watch"      {:summary "Watch repository and auto-update on new commits"
                 :usage   "noum watch <repo> [--interval N]"
                 :min-args 1}
   "ask"        {:summary "Ask a question about a repository using AI"
                 :usage   "noum ask <repo> <question...> [options]"
                 :api-path "/api/ask" :api-method :post :min-args 2
                 :positional-map :ask}
   "query"      {:summary "Run a named or raw Datalog query"
                 :usage   "noum query <name> <repo> [--param key=value] [--as-of <date>]\n  noum query --raw '<datalog>' <repo> [--limit N]"
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
                 :api-path "/api/benchmark" :api-method :post :min-args 1}
   "results"    {:summary "Get benchmark results"
                 :usage   "noum results <repo> [--run-id <id>]"
                 :min-args 1}
   "compare"    {:summary "Compare two benchmark runs"
                 :usage   "noum compare <repo> <run-a> <run-b>"
                 :min-args 3}
   "introspect" {:summary "Run autonomous self-improvement loop"
                 :usage   "noum introspect <repo> [options]\n  noum introspect --status <run-id>\n  noum introspect --stop <run-id>\n  noum introspect --history [<query-name>]"
                 :api-path "/api/introspect" :api-method :post :min-args 1}
   "sessions"   {:summary "List or view past ask sessions"
                 :usage   "noum sessions [<session-id>]"}
   "feedback"   {:summary "Submit feedback on an ask session"
                 :usage   "noum feedback <session-id> positive|negative [--comment \"...\"]"
                 :min-args 2}
   "databases"  {:summary "List all databases"
                 :usage   "noum databases"
                 :api-path "/api/databases" :api-method :get}
   "delete"     {:summary "Delete a database (prompts for confirmation)"
                 :usage   "noum delete <name> [--force]"
                 :min-args 1}
   "settings"   {:summary "View or update settings"
                 :usage   "noum settings [<key> [<value>]]"}
   "reseed"     {:summary "Reload prompts, queries, and rules"
                 :usage   "noum reseed"
                 :api-path "/api/reseed" :api-method :post}
   "history"    {:summary "Show artifact change history"
                 :usage   "noum history rules\n  noum history prompt <name>\n\n  Prompt names: analyze-file, agent-system, introspect"}
   "synthesize" {:summary "Identify architectural components from analyzed data"
                 :usage   "noum synthesize <repo> [options]"
                 :api-path "/api/synthesize" :api-method :post :min-args 1}
   "demo"       {:summary "Download pre-built demo database for instant querying"
                 :usage   "noum demo [--force]\n\n  Downloads a fully analyzed knowledge graph of the Noumenon repository.\n  No LLM credentials needed — try queries immediately:\n\n    noum ask noumenon \"Describe the architecture\"\n    noum query components noumenon\n    noum status noumenon"}
   "setup"      {:summary "Configure MCP for Claude Desktop or Code"
                 :usage   "noum setup <desktop|code>"}
   "serve"      {:summary "Start MCP server (stdin/stdout, for Claude Desktop/Code)"
                 :usage   "noum serve [options]"}
   "start"      {:summary "Start the daemon"
                 :usage   "noum start [options]"}
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
                 :usage   "noum connect <url> --token <token>\n       noum connect local"}
   "connections" {:summary "List configured connections"
                  :usage   "noum connections"}
   "disconnect" {:summary "Remove a saved connection"
                 :usage   "noum disconnect <name>"}})

(def command-groups
  [["Get Started" ["demo" "setup"]]
   ["Pipeline"   ["digest" "import" "analyze" "enrich" "synthesize" "update" "watch"]]
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
         "Usage: " (:usage cmd) "\n")))

;; --- Arg parsing ---

(def ^:private boolean-flags
  "Flags that never take a value — always treated as true when present."
  #{"--skip-import" "--skip-enrich" "--skip-analyze" "--skip-synthesize" "--skip-benchmark"
    "--report" "--force" "--analyze" "--verbose" "--debug" "--canary"
    "--deterministic-only" "--git-commit" "--read-only"})

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
              (recur more (assoc flags key true) positional)
              (recur (rest more) (assoc flags key (first more)) positional)))

          (= "-v" arg)
          (recur more (assoc flags :verbose true) positional)

          :else
          (recur more flags (conj positional arg)))))))

(defn parse-args
  "Parse CLI args. Returns {:command 'name' :flags {} :positional [] :error nil}."
  [args]
  (if-not (seq args)
    {:error :no-args}
    (let [[cmd & rest-args] args
          [flags positional] (extract-flags rest-args)]
      (cond
        (= "--version" cmd) {:command "version"}
        (#{"--help" "-h"} cmd) {:command "help"}
        (commands cmd) {:command cmd :flags flags :positional positional}
        :else {:error :unknown-command :command cmd}))))
