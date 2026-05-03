(ns noumenon.cli.commands.query
  "CLI command handlers for read-side commands — watch (poll-and-sync)
   and the named-query runner."
  (:require [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.cli.util :as cu]
            [noumenon.db :as db]
            [noumenon.query :as query]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]]
            [clojure.string :as str]))

(defn- classify-watch-result
  "Derive watch-loop state from an update result and prior failure count."
  [result failures]
  (let [failed? (= result ::error)]
    {:failed?      failed?
     :had-changes? (and (not failed?) (not= :up-to-date (:status result)))
     :new-failures (if failed? (inc failures) 0)}))

(defn- backoff-multiplier
  "Sleep multiplier: 1 for 0-2 failures, capped at 10 thereafter."
  [failures]
  (if (>= failures 3) (min failures 10) 1))

(defn do-watch
  "Run the watch subcommand. Polls git HEAD and syncs on changes."
  [{:keys [interval analyze] :as opts}]
  (cu/with-valid-repo
    opts
    (fn [{:keys [repo-path db-dir db-name]}]
      (let [conn       (db/connect-and-ensure-schema db-dir db-name)
            meta-conn  (db/ensure-meta-db db-dir)
            repo-uri   (.getCanonicalPath (java.io.File. (str repo-path)))
            interval-s (or interval 30)
            base-opts  (cu/build-sync-opts opts)]
        (log! (str "Watching " repo-path " (polling every " interval-s "s)"))
        (loop [failures 0, last-had-changes? true]
          (let [sync-opts (cond-> base-opts
                            analyze (assoc :meta-db (d/db meta-conn)))
                result (try
                         (sync/update-repo! conn repo-path repo-uri
                                            (assoc sync-opts :quiet? (not last-had-changes?)))
                         (catch Exception e
                           (log! (str "Update error: " (.getMessage e)))
                           ::error))
                {:keys [failed? had-changes? new-failures]} (classify-watch-result result failures)]
            (when (and failed? (= new-failures 5))
              (log! (str "WARNING: 5 consecutive failures. Check database and repository.")))
            (Thread/sleep (* interval-s 1000 (backoff-multiplier new-failures)))
            (recur new-failures (or had-changes? failed?))))))))

(defn- do-query-list
  "List available named queries with descriptions."
  [meta-db]
  (let [queries (->> (artifacts/list-active-query-names meta-db)
                     (mapv (fn [qname]
                             {:name        qname
                              :description (or (:description (artifacts/load-named-query meta-db qname)) "")})))
        width   (+ 4 (reduce max 0 (map (comp count :name) queries)))
        fmt-str (str "  %-" width "s %s")]
    (doseq [{:keys [name description]} queries]
      (log! (format fmt-str name description)))
    {:exit 0 :result queries}))

(defn do-query
  "Run the query subcommand."
  [{:keys [query-name list-queries params] :as opts}]
  (if list-queries
    (let [meta-conn (db/ensure-meta-db (util/resolve-db-dir opts))]
      (do-query-list (d/db meta-conn)))
    (cu/with-valid-repo
      opts
      (fn [ctx]
        (cu/with-existing-db
          ctx
          (fn [{:keys [db meta-db]}]
            (let [kw-params (into {} (map (fn [[k v]] [(keyword k) v])) params)
                  {:keys [ok error]} (query/run-named-query meta-db db query-name kw-params)]
              (if error
                (do (cu/print-error! error)
                    (when (str/starts-with? (str error) "Missing required inputs")
                      (log! "Hint: use --param key=value to supply query inputs."))
                    {:exit 1})
                {:exit 0 :result ok}))))))))
