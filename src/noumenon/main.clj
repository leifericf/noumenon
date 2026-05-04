(ns noumenon.main
  "Top-level CLI dispatcher. Parses argv via noumenon.cli, routes to a
   per-cluster command namespace under noumenon.cli.commands, and
   prints the result map for non-suppressed subcommands."
  (:gen-class)
  (:require [noumenon.cli :as cli]
            [noumenon.cli.commands.artifact :as c-art]
            [noumenon.cli.commands.ask :as c-ask]
            [noumenon.cli.commands.benchmark :as c-bench]
            [noumenon.cli.commands.daemon :as c-daemon]
            [noumenon.cli.commands.digest :as c-digest]
            [noumenon.cli.commands.inspect :as c-insp]
            [noumenon.cli.commands.introspect :as c-intro]
            [noumenon.cli.commands.pipeline :as c-pipe]
            [noumenon.cli.commands.query :as c-query]
            [noumenon.cli.util :as cu]
            [noumenon.util :as util :refer [log!]]))

;; --- Error dispatch ---

(def ^:private error-messages
  {:no-args                      nil
   :unknown-subcommand           #(str "Unknown subcommand: " (:subcommand %)
                                       ". Run '" cli/program-name " --help' for available subcommands.")
   :no-repo-path                 "Missing <repo-path> argument."
   :resume-consumed-repo-path   "Missing <repo-path> argument. Did --resume consume your repo-path? Place --resume after <repo-path>."
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
                                       ". Must be 'glm', 'claude', or 'claude-api'.")
   :invalid-max-cost             #(str "Invalid --max-cost value: " (:value %))
   :missing-max-cost-value       "Missing value for --max-cost."
   :invalid-concurrency          #(str "Invalid --concurrency value: " (:value %) ". Must be 1-20.")
   :missing-concurrency-value    "Missing value for --concurrency."
   :invalid-min-delay            #(str "Invalid --min-delay value: " (:value %) ". Must be >= 0.")
   :missing-min-delay-value      "Missing value for --min-delay."
   :ask-missing-args              "Missing required arguments for ask command."
   :ask-missing-question          "Missing -q <question> argument."
   :invalid-max-iterations       #(str "Invalid --max-iterations value: " (:value %))
   :missing-max-iterations-value "Missing value for --max-iterations."
   :missing-param-value          "Missing value for --param. Use --param key=value."
   :invalid-param-value          #(str "Invalid --param value: " (:value %) ". Expected key=value format.")
   :invalid-max-files            #(str "Invalid --max-files value: " (:value %))
   :missing-max-files-value      "Missing value for --max-files."
   :invalid-limit                #(str "Invalid --limit value: " (:value %))
   :missing-limit-value          "Missing value for --limit."
   :missing-path-value           "Missing value for --path."
   :missing-include-value        "Missing value for --include."
   :missing-exclude-value        "Missing value for --exclude."
   :missing-lang-value           "Missing value for --lang."
   :invalid-interval             #(str "Invalid --interval value: " (:value %) ". Must be a positive integer.")
   :missing-interval-value       "Missing value for --interval."
   :missing-layers-value         "Missing value for --layers. Example: --layers raw,full"
   :invalid-max-hours            #(str "Invalid --max-hours value: " (:value %))
   :missing-max-hours-value      "Missing value for --max-hours."
   :missing-target-value         "Missing value for --target."
   :invalid-eval-runs            #(str "Invalid --eval-runs value: " (:value %) ". Must be a positive integer.")
   :missing-eval-runs-value      "Missing value for --eval-runs."})

(def ^:private errors-with-global-usage
  #{:no-args :unknown-subcommand})

(def ^:private errors-with-subcommand-usage
  #{:no-repo-path :resume-consumed-repo-path :missing-db-dir-value :unknown-flag
    :ask-missing-question :ask-missing-args :query-missing-args
    :missing-param-value :invalid-param-value
    :missing-path-value :missing-include-value :missing-exclude-value :missing-lang-value
    :invalid-concurrency :missing-concurrency-value
    :invalid-min-delay :missing-min-delay-value
    :invalid-max-iterations :missing-max-iterations-value
    :invalid-interval :missing-interval-value
    :missing-layers-value
    :invalid-eval-runs :missing-eval-runs-value})

(defn- handle-parse-error
  "Display error message and usage help for a failed parse. Returns {:exit 1}."
  [{:keys [error subcommand] :as parsed}]
  (when-let [msg-or-fn (error-messages error)]
    (let [msg (if (fn? msg-or-fn) (msg-or-fn parsed) msg-or-fn)]
      (cu/print-error! msg)))
  (cond
    (errors-with-global-usage error)
    (cu/print-usage!)
    (and (errors-with-subcommand-usage error)
         subcommand
         (cli/format-subcommand-help subcommand))
    (do (log!) (log! (cli/format-subcommand-help subcommand)))
    (errors-with-subcommand-usage error)
    (cu/print-usage!))
  {:exit 1})

;; --- Dispatch ---

(def ^:private suppressed-output-subcommands
  #{"benchmark" "serve" "status" "list-databases" "show-schema" "watch" "introspect"})

(defn- dispatch-subcommand [parsed]
  (case (:subcommand parsed)
    "import"           (c-pipe/do-import parsed)
    "analyze"          (c-pipe/do-analyze parsed)
    "enrich"           (c-pipe/do-enrich parsed)
    "synthesize"       (c-pipe/do-synthesize parsed)
    "embed"            (c-pipe/do-embed parsed)
    "update"           (c-pipe/do-update parsed)
    "watch"            (c-query/do-watch parsed)
    "query"            (c-query/do-query parsed)
    "ask"              (c-ask/do-ask parsed)
    "show-schema"      (c-insp/do-show-schema parsed)
    "status"           (c-insp/do-status parsed)
    "llm-providers"    (c-insp/do-llm-providers parsed)
    "llm-models"       (c-insp/do-llm-models parsed)
    "list-databases"   (c-insp/do-list-databases parsed)
    "benchmark"        (c-bench/do-benchmark parsed)
    "digest"           (c-digest/do-digest parsed)
    "introspect"       (c-intro/do-introspect parsed)
    "reseed"           (c-art/do-reseed parsed)
    "artifact-history" (c-art/do-artifact-history parsed)
    "serve"            (c-daemon/do-serve parsed)
    "daemon"           (c-daemon/do-daemon parsed)))

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
      (handle-parse-error parsed)

      :else
      (let [result (dispatch-subcommand parsed)]
        (when (and (:result result)
                   (zero? (:exit result))
                   (not (suppressed-output-subcommands (:subcommand parsed))))
          (prn (:result result)))
        result))))

(defn -main [& args]
  (let [{:keys [exit]} (run args)]
    (System/exit (or exit 0))))
