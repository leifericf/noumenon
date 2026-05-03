(ns noumenon.mcp.handlers.query
  "MCP tool handlers for read-side traffic — repo status, named Datalog
   queries (point and federated), schema dump, and TF-IDF search."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.db :as db]
            [noumenon.delta :as delta]
            [noumenon.embed :as embed]
            [noumenon.mcp.util :as mu]
            [noumenon.query :as query]
            [noumenon.sync :as sync]
            [noumenon.util :as util]))

(defn handle-status [args defaults]
  (mu/with-conn args defaults
    (fn [{:keys [db]}]
      (let [{:keys [commits files dirs head-sha]} (query/repo-stats db)
            head (if head-sha
                   (str "\nHead: " (subs head-sha 0 (min 7 (count head-sha))))
                   "")]
        (mu/tool-result (str commits " commits, " files " files, " dirs " directories." head
                             "\n\nTip: Use noumenon_query or noumenon_ask to explore the codebase before reading files directly."))))))

(defn handle-search [args defaults]
  (util/validate-string-length! "query" (args "query") mu/max-question-len)
  (mu/with-conn args defaults
    (fn [{:keys [db-dir db-name]}]
      (let [idx     (embed/get-cached-index db-dir db-name)
            _       (when-not idx
                      (throw (ex-info "No TF-IDF index found. Run embed (or digest) first."
                                      {:user-message "No TF-IDF index. Run: noumenon embed <repo>"})))
            limit   (min (or (args "limit") 8) 50)
            results (embed/search idx (args "query") :limit limit)]
        (if (seq results)
          (mu/tool-result
           (str "Found " (count results) " results:\n\n"
                (->> results
                     (map-indexed
                      (fn [i {:keys [kind path name text score]}]
                        (str (inc i) ". "
                             (if (= :file kind)
                               (str path " (file)")
                               (str name " (component)"))
                             " — score: " (format "%.3f" (double score))
                             "\n   " (first (str/split-lines text)))))
                     (str/join "\n"))))
          (mu/tool-result "No results found. The index may be empty — run analyze + embed first."))))))

(defn handle-query [args defaults]
  (util/validate-string-length! "query_name" (args "query_name") util/max-query-name-len)
  (mu/with-conn args defaults
    (fn [{:keys [db meta-db]}]
      (let [raw-params (args "params")
            _          (util/validate-params! raw-params)
            params     (into {} (map (fn [[k v]] [(keyword k) v])) raw-params)
            result (query/run-named-query meta-db db (args "query_name") params)]
        (if (:ok result)
          (let [all-rows   (:ok result)
                total      (count all-rows)
                limit      (min (or (some-> (args "limit") long) 500) 10000)
                rows       (take limit all-rows)
                truncated? (> total limit)
                query-name (args "query_name")
                query-def  (artifacts/load-named-query meta-db query-name)
                header     (when-let [cols (:columns query-def)]
                             (str "Columns: " (str/join ", " cols) "\n"))
                summary    (str "Query '" query-name "': " (count rows)
                                (when truncated? (str " of " total))
                                " results\n")]
            (mu/tool-result (str summary header
                                 (str/join "\n" (map pr-str rows))
                                 (when truncated?
                                   (str "\n... truncated (" (- total limit) " more rows). "
                                        "Pass a higher `limit` to see more.")))))
          (mu/tool-error (:error result)))))))

(defn handle-query-federated [args defaults]
  (util/validate-string-length! "query_name" (args "query_name") util/max-query-name-len)
  (util/validate-string-length! "branch" (args "branch") util/max-branch-name-len)
  (let [basis-sha (args "basis_sha")
        branch    (args "branch")]
    (when-not (sync/valid-sha? basis-sha)
      (throw (ex-info "Invalid basis_sha"
                      {:user-message "basis_sha must be a 40-char lowercase hex SHA"})))
    (mu/with-conn args defaults
      (fn [{:keys [db meta-db repo-path db-name]}]
        (let [raw-params (args "params")
              _          (util/validate-params! raw-params)
              params     (into {} (map (fn [[k v]] [(keyword k) v])) raw-params)
              ;; Auto-derive parent metadata so the delta's branch entity
              ;; carries the lineage breadcrumb. parent-host is "local"
              ;; because MCP runs in-process — no over-the-wire host to
              ;; record. Mirrors the http.clj/federated-delta-opts shape.
              delta-opts (cond-> {:parent-db-name db-name
                                  :parent-host    "local"}
                           branch (assoc :branch-name branch))
              delta-conn (delta/ensure-delta-db! repo-path basis-sha delta-opts)
              _          (delta/update-delta! delta-conn repo-path basis-sha delta-opts)
              delta-db   (d/db delta-conn)
              query-name (args "query_name")
              result     (query/run-federated-query meta-db db delta-db query-name params)]
          (if (:error result)
            (mu/tool-error (:error result))
            (let [all-rows   (:ok result)
                  total      (count all-rows)
                  limit      (min (or (some-> (args "limit") long) 500) 10000)
                  rows       (take limit all-rows)
                  truncated? (> total limit)
                  query-def  (artifacts/load-named-query meta-db query-name)
                  header     (when-let [cols (:columns query-def)]
                               (str "Columns: " (str/join ", " cols) "\n"))
                  fsafe?     (:federation-safe? result)
                  summary    (str "Federated '" query-name "' "
                                  "(trunk: " (:trunk-count result) ", "
                                  "delta: " (:delta-count result)
                                  (when-not fsafe? ", NOT federation-safe — trunk-only")
                                  "): " (count rows)
                                  (when truncated? (str " of " total))
                                  " results\n")]
              (mu/tool-result (str summary header
                                   (str/join "\n" (map pr-str rows))
                                   (when truncated?
                                     (str "\n... truncated (" (- total limit) " more rows). "
                                          "Pass a higher `limit` to see more.")))))))))))

(defn handle-list-queries [_args defaults]
  (let [db-dir    (util/resolve-db-dir defaults)
        meta-conn (db/ensure-meta-db db-dir)
        meta-db   (d/db meta-conn)
        lines     (->> (artifacts/list-active-query-names meta-db)
                       (keep (fn [n]
                               (when-let [q (artifacts/load-named-query meta-db n)]
                                 (str n " — " (:description q "no description")
                                      (when (seq (:inputs q))
                                        (str " [requires params: " (str/join ", " (map name (:inputs q))) "]")))))))]
    (mu/tool-result
     (if (seq lines)
       (str "Available queries (pass the name to noumenon_query):\n"
            (str/join "\n" lines))
       "No named queries found. First call noumenon_update with your repo_path to initialize the knowledge graph, then retry. If queries are still missing, call noumenon_reseed."))))

(defn handle-get-schema [args defaults]
  (mu/with-conn args defaults
    (fn [{:keys [db]}]
      (mu/tool-result (query/schema-summary db)))))
