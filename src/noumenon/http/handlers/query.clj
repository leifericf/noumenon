(ns noumenon.http.handlers.query
  "HTTP handlers for read-side traffic — named Datalog queries (point,
   raw, as-of, federated), the queries catalog, schema/status, the
   LLM-driven `ask` agent, and prefix completions for the launcher."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.agent :as agent]
            [noumenon.artifacts :as artifacts]
            [noumenon.ask-store :as ask-store]
            [noumenon.db :as db]
            [noumenon.delta :as delta]
            [noumenon.embed :as embed]
            [noumenon.http.middleware :as mw]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]]))

(defn- run-ask [{:keys [db meta-db meta-conn db-dir db-name]} params config progress-fn]
  (let [{:keys [invoke-fn]}
        (llm/make-messages-fn-from-opts
         (merge (mw/resolve-provider params config)
                {:temperature 0.3 :max-tokens 4096}))
        max-iter   (min (or (:max_iterations params) 10) 50)
        eidx       (embed/get-cached-index db-dir db-name)
        started-at (java.util.Date.)
        start-ms   (System/currentTimeMillis)
        channel    (get #{:ui :cli :mcp :api} (keyword (or (:channel params) "")) :unknown)
        caller     (get #{:human :ai-agent} (keyword (or (:caller params) "")) :unknown)
        result     (agent/ask meta-db db (:question params)
                              {:invoke-fn      invoke-fn
                               :repo-name      db-name
                               :embed-index    eidx
                               :max-iterations max-iter
                               :continue-from  (:continue_from params)
                               :on-iteration   progress-fn})
        duration   (- (System/currentTimeMillis) start-ms)
        session-id (try
                     (ask-store/save-session!
                      meta-conn result
                      {:channel     channel
                       :caller      caller
                       :repo        db-name
                       :question    (:question params)
                       :started-at  started-at
                       :duration-ms duration})
                     (catch Exception e
                       (log! "ask-store/error" (.getMessage e))
                       nil))]
    {:answer     (:answer result)
     :status     (:status result)
     :session-id session-id
     :usage      (:usage result)}))

(defn handle-ask [request config]
  (let [params (mw/parse-json-body request)]
    (when (or (nil? (:question params))
              (and (string? (:question params))
                   (str/blank? (:question params))))
      (throw (ex-info "Missing question" {:status 400 :message "question is required"})))
    (mw/validate-string-length! "question" (:question params) mw/max-question-len)
    (mw/with-repo params (:db-dir config)
      (fn [ctx]
        (if (mw/wants-sse? request)
          (mw/with-sse request (partial run-ask ctx params config))
          (mw/ok (run-ask ctx params config nil)))))))

(defn handle-query-exec [request config]
  (let [params (mw/parse-json-body request)]
    (mw/validate-string-length! "query_name" (:query_name params) mw/max-query-name-len)
    (mw/with-repo params (:db-dir config)
      (fn [{:keys [db meta-db]}]
        (let [query-name    (:query_name params)
              raw-params    (or (:params params) {})
              kw-params     (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) raw-params)
              _             (mw/validate-query-params! kw-params)
              exclude-paths (mw/validate-exclude-paths! (:exclude_paths params))
              result        (query/run-named-query meta-db db query-name kw-params
                                                   {:exclude-paths exclude-paths})]
          (if (:ok result)
            (let [rows  (:ok result)
                  limit (min (or (some-> (:limit params) str parse-long) 500) 10000)]
              (mw/ok {:query            query-name
                      :total            (count rows)
                      :federation-safe? (:federation-safe? result)
                      :results          (take limit rows)}))
            (mw/error-response 400 (:error result))))))))

(defn handle-query-raw [request config]
  (let [params (mw/parse-json-body request)]
    (mw/with-repo params (:db-dir config)
      (fn [{:keys [db]}]
        (let [query-edn (:query params)
              limit     (min (or (some-> (:limit params) str parse-long) 500) 10000)]
          (when-not query-edn
            (throw (ex-info "Missing query" {:status 400 :message "query is required (EDN string)"})))
          (mw/validate-string-length! "query" query-edn mw/max-param-value-len)
          ;; Raw queries are not federation-safe in v1: column types are unknown,
          ;; so we can't guarantee the launcher can row-merge with delta DB rows.
          (mw/validate-exclude-paths! (:exclude_paths params))
          (let [parsed (try (edn/read-string {:readers {}} query-edn)
                            (catch Exception e
                              (throw (ex-info "Invalid EDN"
                                              {:status 400 :message (str "Invalid EDN: " (.getMessage e))}))))
                _       (when-let [err (agent/validate-query parsed)]
                          (throw (ex-info err {:status 400 :message err})))
                args    (or (:args params) [])
                results (try (apply d/q parsed db args)
                             (catch Exception e
                               (throw (ex-info "Query failed"
                                               {:status 400 :message (str "Query error: " (.getMessage e))}))))]
            (mw/ok {:total            (count results)
                    :federation-safe? false
                    :results          (take limit (vec results))})))))))

(defn handle-query-as-of [request config]
  (let [params    (mw/parse-json-body request)
        as-of-str (:as_of params)]
    (mw/validate-string-length! "query_name" (:query_name params) mw/max-query-name-len)
    (when-not as-of-str
      (throw (ex-info "Missing as_of" {:status 400 :message "as_of is required (ISO-8601 or epoch ms)"})))
    (when-not (or (string? as-of-str) (number? as-of-str))
      (throw (ex-info "Invalid as_of"
                      {:status 400
                       :message "as_of must be an ISO-8601 string or epoch milliseconds"})))
    (let [as-of-inst (try
                       (if (string? as-of-str)
                         (java.util.Date/from (java.time.Instant/parse as-of-str))
                         (java.util.Date. (long as-of-str)))
                       (catch Exception e
                         (throw (ex-info "Invalid as_of"
                                         {:status 400 :message (str "Invalid as_of: " (.getMessage e))}))))]
      (mw/with-repo params (:db-dir config)
        (fn [{:keys [conn meta-db]}]
          (let [query-name    (:query_name params)
                raw-params    (or (:params params) {})
                kw-params     (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) raw-params)
                _             (mw/validate-query-params! kw-params)
                exclude-paths (mw/validate-exclude-paths! (:exclude_paths params))
                limit         (min (or (some-> (:limit params) str parse-long) 500) 10000)
                db            (d/as-of (d/db conn) as-of-inst)
                result        (query/run-named-query meta-db db query-name kw-params
                                                     {:exclude-paths exclude-paths})]
            (if (:ok result)
              (let [rows (:ok result)]
                (mw/ok {:query            query-name
                        :as-of            (str as-of-inst)
                        :total            (count rows)
                        :federation-safe? (:federation-safe? result)
                        :results          (take limit rows)}))
              (mw/error-response 400 (:error result)))))))))

(defn- federated-delta-opts
  "Build opts for `delta/update-delta!` from a federated-query request."
  [request trunk-db-name branch]
  (cond-> {:parent-db-name trunk-db-name}
    branch                              (assoc :branch-name branch)
    (get-in request [:headers "host"])  (assoc :parent-host
                                               (get-in request [:headers "host"]))))

(defn handle-query-federated
  "Run a named query over both trunk and a local delta DB, then concatenate
   delta rows on top of trunk rows. Trunk is queried with :exclude-paths
   covering every :file/path in the delta, so a path appears in at most one
   side of the merge.

   Federation requires the query to be marked :federation-safe? in its EDN.
   Non-federation-safe queries return trunk-only with :federation-safe? false."
  [request config]
  (let [params      (mw/parse-json-body request)
        repo-path   (:repo_path params)
        basis-sha   (:basis_sha params)
        query-name  (:query_name params)
        branch      (:branch params)
        raw-params  (or (:params params) {})
        kw-params   (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) raw-params)]
    (when-not (and repo-path basis-sha query-name)
      (throw (ex-info "Missing required fields"
                      {:status 400
                       :message "repo_path, basis_sha, and query_name are required"})))
    (mw/validate-string-length! "query_name" query-name mw/max-query-name-len)
    (mw/validate-string-length! "branch" branch mw/max-branch-name-len)
    (when-not (sync/valid-sha? basis-sha)
      (throw (ex-info "Invalid basis_sha"
                      {:status 400 :message "basis_sha must be a 40-char lowercase hex SHA"})))
    (when-let [reason (util/validate-repo-path repo-path)]
      (throw (ex-info reason {:status 400 :message (str "repo_path " reason)})))
    (mw/validate-query-params! kw-params)
    (mw/with-repo {:repo_path repo-path} (:db-dir config)
      (fn [{:keys [db meta-db db-name]}]
        (let [delta-opts (federated-delta-opts request db-name branch)
              delta-conn (delta/ensure-delta-db! repo-path basis-sha delta-opts)
              _          (delta/update-delta! delta-conn repo-path basis-sha delta-opts)
              delta-db   (d/db delta-conn)
              limit      (min (or (some-> (:limit params) str parse-long) 500) 10000)
              result     (query/run-federated-query meta-db db delta-db query-name kw-params)]
          (if (:error result)
            (mw/error-response 400 (:error result))
            (mw/ok {:query            query-name
                    :total            (count (:ok result))
                    :trunk-count      (:trunk-count result)
                    :delta-count      (:delta-count result)
                    :basis-sha        basis-sha
                    :federation-safe? (:federation-safe? result)
                    :results          (take limit (:ok result))})))))))

(defn handle-queries [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))
        meta-db   (d/db meta-conn)
        queries   (->> (artifacts/list-active-query-names meta-db)
                       (keep (fn [n]
                               (when-let [q (artifacts/load-named-query meta-db n)]
                                 {:name        n
                                  :description (:description q)
                                  :inputs      (mapv name (:inputs q []))}))))]
    (mw/ok queries)))

(defn handle-schema [request config]
  (let [db-name (get-in request [:params :repo])]
    (mw/with-db-name db-name (:db-dir config)
      (fn [{:keys [db]}]
        (mw/ok {:schema (query/schema-summary db)})))))

(defn handle-status [request config]
  (let [db-name (get-in request [:params :repo])]
    (mw/with-db-name db-name (:db-dir config)
      (fn [{:keys [db]}]
        (mw/ok (query/repo-stats db))))))

;; --- Completions (prefix search across files/dirs/authors/commits/segments/queries) ---

(defonce ^:private completion-cache (atom {}))

(defn- get-completion-data
  "Load or return cached completion source data for a repo. 60s TTL."
  [db meta-db db-name]
  (let [cached (get @completion-cache db-name)]
    (if (and cached (> (:ttl cached) (System/currentTimeMillis)))
      (:data cached)
      (let [data {:files   (->> (d/q '[:find ?path :where [?f :file/path ?path]] db)
                                (mapv first))
                  :dirs    (->> (d/q '[:find ?path :where [?d :dir/path ?path]] db)
                                (mapv first))
                  :authors (->> (d/q '[:find ?name :where [?p :person/name ?name]] db)
                                (mapv first))
                  :commits (->> (d/q '[:find ?sha ?msg
                                       :where [?c :git/type :commit]
                                       [?c :git/sha ?sha] [?c :commit/message ?msg]] db)
                                vec)
                  :segments (->> (d/q '[:find ?name ?kind
                                        :where [?s :code/name ?name] [?s :code/kind ?kind]] db)
                                 vec)
                  :queries (vec (artifacts/list-active-query-names meta-db))}]
        (swap! completion-cache assoc db-name
               {:data data :ttl (+ (System/currentTimeMillis) 60000)})
        data))))

(defn handle-completions [request config]
  (let [params (:query-params request)
        prefix (or (:prefix params) "")
        repo   (:repo_path params)]
    (when-not repo
      (throw (ex-info "Missing repo_path" {:status 400 :message "repo_path is required"})))
    (mw/with-repo {:repo_path repo} (:db-dir config)
      (fn [{:keys [db meta-db db-name]}]
        (let [src       (get-completion-data db meta-db db-name)
              lc-prefix (.toLowerCase ^String prefix)
              files    (->> (:files src)
                            (filter #(.contains (.toLowerCase ^String %) lc-prefix))
                            (sort)
                            (take 10))
              authors  (->> (:authors src)
                            (filter #(.contains (.toLowerCase ^String %) lc-prefix))
                            (sort)
                            (take 5))
              commits  (->> (:commits src)
                            (filter (fn [[sha msg]]
                                      (or (.startsWith ^String sha lc-prefix)
                                          (.contains (.toLowerCase ^String msg) lc-prefix))))
                            (take 5))
              dirs     (->> (:dirs src)
                            (filter #(.contains (.toLowerCase ^String %) lc-prefix))
                            (sort)
                            (take 5))
              queries  (->> (:queries src)
                            (filter #(.contains (.toLowerCase ^String %) lc-prefix))
                            (take 5))
              segments (->> (:segments src)
                            (filter (fn [[n _]] (.contains (.toLowerCase ^String n) lc-prefix)))
                            (take 5))
              items   (concat
                       (map (fn [[sha msg]]
                              {:type "commit"
                               :value (subs sha 0 (min 7 (count sha)))
                               :label (subs msg 0 (min 60 (count msg)))})
                            commits)
                       (map (fn [[n kind]] {:type (name kind) :value n}) segments)
                       (map (fn [q] {:type "query" :value q}) queries)
                       (map (fn [a] {:type "author" :value a}) authors)
                       (map (fn [p] {:type "file" :value p}) files)
                       (map (fn [d] {:type "dir" :value d}) dirs))]
          (mw/ok (vec (take 15 items))))))))
