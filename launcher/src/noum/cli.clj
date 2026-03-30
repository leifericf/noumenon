(ns noum.cli
  "Command definitions, arg parsing, and help text for the noum launcher."
  (:require [clojure.string :as str]))

;; --- Command registry ---

(def commands
  "All noum commands. Each key is the command name (one word, no hyphens)."
  {"digest"     {:summary "Run full pipeline: import, enrich, analyze, benchmark"
                 :usage   "noum digest <repo> [options]"
                 :api-path "/api/digest" :api-method :post :min-args 1}
   "import"     {:summary "Import git history and file structure"
                 :usage   "noum import <repo> [options]"
                 :api-path "/api/import" :api-method :post :min-args 1}
   "analyze"    {:summary "Run LLM semantic analysis on repository files"
                 :usage   "noum analyze <repo> [options]"
                 :api-path "/api/analyze" :api-method :post :min-args 1}
   "enrich"     {:summary "Extract cross-file import graph deterministically"
                 :usage   "noum enrich <repo> [options]"
                 :api-path "/api/enrich" :api-method :post :min-args 1}
   "update"     {:summary "Sync knowledge graph with latest git state"
                 :usage   "noum update <repo> [options]"
                 :api-path "/api/update" :api-method :post :min-args 1}
   "watch"      {:summary "Watch repository and auto-update on new commits"
                 :usage   "noum watch <repo> [--interval N]"
                 :min-args 1}
   "ask"        {:summary "Ask a question about a repository using AI"
                 :usage   "noum ask <repo> \"question\" [options]"
                 :api-path "/api/ask" :api-method :post :min-args 2
                 :positional-map :ask}
   "query"      {:summary "Run a named Datalog query"
                 :usage   "noum query <name> <repo> [--param key=value]"
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
                 :usage   "noum introspect <repo> [options]"
                 :api-path "/api/introspect" :api-method :post :min-args 1}
   "databases"  {:summary "List all databases"
                 :usage   "noum databases"
                 :api-path "/api/databases" :api-method :get}
   "delete"     {:summary "Delete a database (prompts for confirmation)"
                 :usage   "noum delete <name> [--force]"
                 :min-args 1}
   "reseed"     {:summary "Reload prompts, queries, and rules"
                 :usage   "noum reseed"
                 :api-path "/api/reseed" :api-method :post}
   "history"    {:summary "Show artifact change history"
                 :usage   "noum history rules\n  noum history prompt <name>\n\n  Prompt names: analyze-file, agent-system, introspect"}
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
   "help"       {:summary "Show help"
                 :usage   "noum help [command]"}
   "version"    {:summary "Show version"
                 :usage   "noum version"}})

(def ^:private command-groups
  [["Pipeline"   ["digest" "import" "analyze" "enrich" "update" "watch"]]
   ["Query"      ["ask" "query" "queries" "schema" "status"]]
   ["Benchmark"  ["bench" "results" "compare"]]
   ["Introspect" ["introspect"]]
   ["Admin"      ["databases" "delete" "reseed" "history"]]
   ["Setup"      ["setup" "serve"]]
   ["Daemon"     ["start" "stop" "ping" "upgrade"]]
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

(defn- extract-flags
  "Extract --flag value pairs from args. Returns [flags-map remaining-args]."
  [args]
  (loop [remaining args, flags {}, positional []]
    (if (empty? remaining)
      [flags positional]
      (let [[arg & more] remaining]
        (cond
          (= "--" arg)
          [flags (into positional more)]

          (str/starts-with? arg "--")
          (let [key (keyword (subs arg 2))]
            (if (or (empty? more) (str/starts-with? (first more) "--"))
              (recur more (assoc flags key true) positional)
              (recur (rest more) (assoc flags key (first more)) positional)))

          (= "-v" arg)
          (recur more (assoc flags :verbose true) positional)

          :else
          (recur more flags (conj positional arg)))))))

(defn parse-args
  "Parse CLI args. Returns {:command 'name' :flags {} :positional [] :error nil}."
  [args]
  (if (empty? args)
    {:error :no-args}
    (let [[cmd & rest-args] args
          [flags positional] (extract-flags rest-args)]
      (cond
        (= "--version" cmd) {:command "version"}
        (#{"--help" "-h"} cmd) {:command "help"}
        (commands cmd) {:command cmd :flags flags :positional positional}
        :else {:error :unknown-command :command cmd}))))
