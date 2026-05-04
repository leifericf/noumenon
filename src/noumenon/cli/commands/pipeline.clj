(ns noumenon.cli.commands.pipeline
  "CLI command handlers for the core pipeline — import / analyze / enrich /
   synthesize / embed / update."
  (:require [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.cli :as cli]
            [noumenon.cli.util :as cu]
            [noumenon.db :as db]
            [noumenon.embed :as embed]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.imports :as imports]
            [noumenon.llm :as llm]
            [noumenon.sync :as sync]
            [noumenon.synthesize :as synthesize]
            [noumenon.util :refer [log!]]))

(defn do-import
  "Run the import subcommand. Returns {:exit n :result map-or-nil}."
  [opts]
  (cu/with-valid-repo
    (update opts :repo-path cu/resolve-repo-path)
    (fn [{:keys [repo-path db-dir db-name] :as ctx}]
      (let [conn     (db/connect-and-ensure-schema db-dir db-name)
            repo-uri (.getCanonicalPath (java.io.File. (str repo-path)))
            git-r    (git/import-commits! conn repo-path repo-uri)
            files-r  (files/import-files! conn repo-path repo-uri)
            head-sha (git/head-sha repo-path)]
        (when head-sha
          (d/transact conn {:tx-data [{:repo/uri repo-uri :repo/head-sha head-sha}]}))
        (log! (str "Next: run '" cli/program-name " enrich " repo-path
                   "' to build the import graph, then '"
                   cli/program-name " analyze " repo-path
                   "' for semantic metadata."))
        {:exit   0
         :result (merge (select-keys git-r [:commits-imported :commits-skipped])
                        (select-keys files-r [:files-imported :files-skipped :dirs-imported])
                        {:db-path    (cu/db-path ctx)
                         :next-step  (str cli/program-name " enrich " repo-path)})}))))

;; Reanalyze scope set + retract helper live in noumenon.sync so the
;; CLI, HTTP, and MCP surfaces all share one definition.

(defn- build-analyze-opts
  "Build the options map for analyze-repo! from CLI opts."
  [{:keys [concurrency min-delay max-files path include exclude lang]
    :or   {concurrency 3 min-delay 0}} model-id provider meta-db]
  (cond-> {:meta-db      meta-db
           :model-id     model-id
           :provider     provider
           :concurrency  concurrency
           :min-delay-ms min-delay
           :path         path
           :include      include
           :exclude      exclude
           :lang         lang}
    max-files (assoc :max-files max-files)))

(defn do-analyze
  "Run the analyze subcommand."
  [{:keys [repo-path model provider reanalyze] :as opts}]
  (when (and reanalyze (not (sync/valid-reanalyze-scopes reanalyze)))
    (cu/print-error! (str "Invalid --reanalyze scope: " reanalyze
                          ". Must be one of: all, prompt-changed, model-changed, stale"))
    (System/exit 1))
  (cu/with-valid-repo
    opts
    (fn [ctx]
      (try
        (cu/with-existing-db
          ctx
          (fn [{:keys [conn meta-db]}]
            (let [{:keys [prompt-fn model-id provider-kw]}
                  (llm/wrap-as-prompt-fn-from-opts {:provider provider :model model})
                  prompt-hash (analyze/prompt-hash (:template (analyze/load-prompt-template meta-db)))]
              (sync/prepare-reanalysis! conn (d/db conn) reanalyze
                                        {:prompt-hash prompt-hash :model-id model-id})
              (let [result (analyze/analyze-repo! conn repo-path prompt-fn
                                                  (build-analyze-opts opts model-id (name provider-kw) meta-db))]
                (log! (str "Next: run '" cli/program-name " query <query-name> " repo-path
                           "' or '" cli/program-name " ask -q \"...\" " repo-path
                           "' to explore the knowledge graph."))
                {:exit 0 :result result}))))
        (catch clojure.lang.ExceptionInfo e
          (cu/print-error! (.getMessage e))
          (when-let [help (cli/format-subcommand-help "analyze")]
            (log!)
            (log! help))
          {:exit 1})))))

(defn do-enrich
  "Run the enrich subcommand."
  [{:keys [concurrency path include exclude lang] :as opts}]
  (cu/with-valid-repo
    opts
    (fn [ctx]
      (cu/with-existing-db
        ctx
        (fn [{:keys [conn repo-path]}]
          (let [result (imports/enrich-repo! conn repo-path
                                             {:concurrency (or concurrency 8)
                                              :path path :include include
                                              :exclude exclude :lang lang})]
            (log! (str "Next: run '" cli/program-name " analyze " repo-path
                       "' for semantic metadata, then '" cli/program-name
                       " query file-imports " repo-path "' to explore."))
            {:exit 0 :result result}))))))

(defn do-synthesize
  "Run the synthesize subcommand."
  [{:keys [model provider] :as opts}]
  (cu/with-valid-repo
    opts
    (fn [ctx]
      (try
        (cu/with-existing-db
          ctx
          (fn [{:keys [conn meta-db repo-path db-name]}]
            (artifacts/reseed! (db/ensure-meta-db (:db-dir ctx)))
            (let [{:keys [prompt-fn model-id provider-kw]}
                  (llm/wrap-as-prompt-fn-from-opts {:provider provider :model model
                                                    :max-tokens 16384})
                  result (synthesize/synthesize-repo!
                          conn prompt-fn
                          {:meta-db   meta-db
                           :provider  (name provider-kw)
                           :model-id  model-id
                           :repo-name db-name})]
              (log! (str "Next: run '" cli/program-name " query components " repo-path
                         "' to explore the architecture."))
              {:exit 0 :result result})))
        (catch clojure.lang.ExceptionInfo e
          (cu/print-error! (.getMessage e))
          (when-let [help (cli/format-subcommand-help "synthesize")]
            (log!)
            (log! help))
          {:exit 1})))))

(defn do-embed
  "Run the embed subcommand: build TF-IDF vector index."
  [opts]
  (cu/with-valid-repo
    opts
    (fn [{:keys [db-dir db-name] :as ctx}]
      (cu/with-existing-db
        ctx
        (fn [{:keys [db]}]
          (let [idx (embed/build-index! db db-dir db-name)]
            {:exit   0
             :result {:entries (count (:entries idx))
                      :vocab   (count (:vocab idx))}}))))))

(defn do-update
  "Run the update subcommand."
  [{:keys [analyze] :as opts}]
  (cu/with-valid-repo
    (update opts :repo-path cu/resolve-repo-path)
    (fn [{:keys [repo-path db-dir db-name]}]
      (let [conn      (db/connect-and-ensure-schema db-dir db-name)
            meta-conn (db/ensure-meta-db db-dir)
            repo-uri  (.getCanonicalPath (java.io.File. (str repo-path)))
            sync-opts (cond-> (cu/build-sync-opts opts)
                        analyze (assoc :meta-db (d/db meta-conn)))
            result    (sync/update-repo! conn repo-path repo-uri sync-opts)]
        (if analyze
          (log! (str "Next: run '" cli/program-name " ask -q \"...\" " repo-path
                     "' or '" cli/program-name " query <query-name> " repo-path
                     "' to explore the updated graph."))
          (log! (str "Next: run '" cli/program-name " analyze " repo-path
                     "' to enrich with semantic metadata.")))
        {:exit 0 :result result}))))
