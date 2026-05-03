(ns noumenon.cli.commands.artifact
  "CLI command handlers for artifact maintenance — reseed and history."
  (:require [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.cli.util :as cu]
            [noumenon.db :as db]
            [noumenon.util :as util :refer [log!]]))

(defn do-reseed
  "Reseed artifacts from classpath into the meta database."
  [opts]
  (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir opts))]
    (artifacts/reseed! meta-conn)
    (let [meta-db (d/db meta-conn)]
      (log! (str "Reseeded: " (count (artifacts/list-active-query-names meta-db))
                 " queries, rules, and prompts."))
      {:exit 0})))

(defn do-artifact-history
  "Show change history for an artifact."
  [{:keys [artifact-type artifact-name] :as opts}]
  (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir opts))
        history   (case artifact-type
                    "prompt" (artifacts/prompt-history meta-conn artifact-name)
                    "rules"  (artifacts/rules-history meta-conn)
                    (do (cu/print-error! (str "Unknown artifact type: " artifact-type
                                              ". Must be 'prompt' or 'rules'."))
                        nil))]
    (if history
      (do (doseq [{:keys [tx-time source]} history]
            (log! (str "  " tx-time " [" (name source) "]")))
          {:exit 0 :result history})
      {:exit 1})))
