(ns noumenon.mcp.handlers.meta
  "MCP tool handlers for the meta/lifecycle cluster — DB inventory, LLM
   provider/model discovery, and artifact (prompt/rules) bookkeeping."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.db :as db]
            [noumenon.llm :as llm]
            [noumenon.mcp.util :as mu]
            [noumenon.util :as util]))

(defn- format-pipeline-stages
  "Format pipeline stages as [import:3 analyze:42 enrich:1], or nil."
  [ops]
  (let [stages (keep (fn [op]
                       (when-let [n (ops op)]
                         (str (name op) ":" n)))
                     [:import :enrich :analyze :synthesize])]
    (when (seq stages)
      (str " [" (str/join " " stages) "]"))))

(defn handle-list-databases [_args defaults]
  (let [db-dir (util/resolve-db-dir defaults)
        names  (db/list-db-dirs db-dir)]
    (if (seq names)
      (let [client (db/create-client db-dir)
            stats  (mapv #(db/db-stats client %) names)]
        (mu/tool-result
         (str/join "\n"
                   (map (fn [{:keys [name commits files dirs cost ops error]}]
                          (if error
                            (str name " (error: " error ")")
                            (str name ": " commits " commits, " files " files, " dirs " dirs"
                                 (when (pos? cost) (str ", $" (format "%.2f" cost)))
                                 (format-pipeline-stages ops))))
                        stats))))
      (mu/tool-result "No databases found."))))

(defn handle-llm-providers [_args _defaults]
  (mu/tool-result (pr-str (llm/provider-catalog))))

(defn handle-llm-models [args _defaults]
  (let [provider (or (args "provider") (llm/default-provider-name))]
    (mu/tool-result (pr-str (llm/discover-provider-models provider)))))

(defn handle-reseed [_args defaults]
  (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir defaults))]
    (artifacts/reseed! meta-conn)
    (let [meta-db (d/db meta-conn)]
      (mu/tool-result (str "Reseeded: " (count (artifacts/list-active-query-names meta-db))
                           " queries, rules, and prompts.")))))

(defn handle-artifact-history [args defaults]
  (let [atype (args "type")
        aname (args "name")]
    (when-not (#{"prompt" "rules"} atype)
      (throw (ex-info "Invalid type" {:user-message "type must be 'prompt' or 'rules'"})))
    (when (and (= atype "prompt") (nil? aname))
      (throw (ex-info "Missing name"
                      {:user-message "name is required when type is 'prompt'. Provide the prompt name to look up."})))
    (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir defaults))
          history   (case atype
                      "prompt" (do (util/validate-string-length! "name" aname 256)
                                   (artifacts/prompt-history meta-conn aname))
                      "rules"  (artifacts/rules-history meta-conn))]
      (mu/tool-result
       (if (seq history)
         (->> history
              (map (fn [{:keys [tx-time source]}]
                     (str tx-time " [" (name source) "]")))
              (str/join "\n"))
         "No history found.")))))
