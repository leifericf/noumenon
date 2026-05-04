(ns noumenon.cli.commands.inspect
  "CLI handlers that inspect existing state — schema, status, LLM
   provider/model catalog, and database listing."
  (:require [clojure.string :as str]
            [noumenon.cli :as cli]
            [noumenon.cli.util :as cu]
            [noumenon.db :as db]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.util :as util :refer [log!]]))

(defn do-show-schema
  "Run the show-schema subcommand."
  [opts]
  (cu/with-valid-repo
    opts
    (fn [ctx]
      (cu/with-existing-db
        ctx
        (fn [{:keys [db]}]
          (let [summary (query/schema-summary db)]
            (println summary)
            {:exit 0 :result summary}))))))

(defn do-status
  "Run the status subcommand."
  [opts]
  (cu/with-valid-repo
    opts
    (fn [ctx]
      (cu/with-existing-db
        ctx
        (fn [{:keys [db] :as c}]
          (let [stats (merge (query/repo-stats db)
                             {:db-path (cu/db-path c)})
                head  (when-let [sha (:head-sha stats)]
                        (str " -- head: " (subs sha 0 (min 7 (count sha)))))]
            (log! (str (:commits stats) " commits, "
                       (:files stats) " files, "
                       (:dirs stats) " directories"
                       " -- db: " (:db-path stats)
                       head))
            {:exit 0 :result stats}))))))

(defn do-llm-providers
  "Show configured LLM providers, models, and defaults."
  [_opts]
  {:exit 0 :result (llm/provider-catalog)})

(defn do-llm-models
  "Show provider models using API discovery when available."
  [opts]
  (let [provider (or (:provider opts) (llm/default-provider-name))]
    {:exit 0 :result (llm/discover-provider-models provider)}))

(defn- format-date [inst]
  (when inst
    (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") inst)))

(defn- format-pipeline
  "Format pipeline stages as [import:3 analyze:42 enrich:1]."
  [ops]
  (let [stages (keep (fn [op]
                       (when-let [n (ops op)]
                         (str (clojure.core/name op) ":" n)))
                     [:import :enrich :analyze :synthesize])]
    (when (seq stages)
      (str "  [" (str/join " " stages) "]"))))

(defn- print-db-stats
  [{:keys [name commits files dirs latest cost ops error]}]
  (if error
    (log! (format "  %-24s (error: %s)" name error))
    (let [date-str  (if latest (str "  (latest: " (format-date latest) ")") "")
          cost-str  (if (pos? cost) (format "  $%.2f" cost) "")
          stage-str (or (format-pipeline ops) "")]
      (log! (format "  %-24s %d commits, %d files, %d dirs%s%s%s"
                    name commits files dirs date-str cost-str stage-str)))))

(defn do-list-databases
  "List all databases or delete one."
  [opts]
  (let [db-dir (util/resolve-db-dir opts)]
    (if-let [db-name (:delete opts)]
      (cond
        (= db-name db/meta-db-name)
        (do (cu/print-error! (str "Cannot delete reserved database: " db-name))
            {:exit 1})

        (not (cu/db-exists? db-dir db-name))
        (do (cu/print-error! (str "Database \"" db-name "\" not found.")) {:exit 1})

        :else
        (let [client (db/create-client db-dir)]
          (db/delete-db client db-name)
          (db/evict-conn! db-dir db-name)
          (log! (str "Deleted database \"" db-name "\"."))
          (log! "WARNING: All analysis data has been destroyed. Re-running analyze may be expensive.")
          (log! (str "Re-import: " cli/program-name " import <repo-path>"))
          {:exit 0}))
      (let [names (db/list-db-dirs db-dir)]
        (if (seq names)
          (let [client (db/create-client db-dir)
                stats  (mapv #(db/db-stats client %) names)]
            (doseq [s stats] (print-db-stats s))
            {:exit 0 :result stats})
          (do (log! (str "No databases found in " db-dir)) {:exit 0 :result []}))))))
