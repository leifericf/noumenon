(ns noumenon.http
  "HTTP API for the Noumenon daemon. Serves REST-ish JSON endpoints
   on localhost for the `noum` CLI launcher and future Electron UI."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [org.httpkit.server :as server]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.benchmark :as bench]
            [noumenon.db :as db]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.imports :as imports]
            [noumenon.introspect :as introspect]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.sessions :as sessions]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]])
  (:import [java.lang ProcessHandle]))

;; --- JSON response helpers ---

(defn- json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json"
             "Cache-Control" "no-cache"}
   :body    (json/write-str body)})

(defn- ok [data]
  (json-response 200 {:ok true :data data}))

(defn- error-response [status message]
  (json-response status {:ok false :error message}))

(defn- parse-json-body [request]
  (when-let [body (:body request)]
    (let [s (slurp body)]
      (when (seq s)
        (json/read-str s :key-fn keyword)))))

;; --- Auth ---

(defn- check-auth
  "Validate bearer token when the daemon is configured with one.
   Returns nil if auth passes, or an error response map."
  [request token]
  (when token
    (let [header (get-in request [:headers "authorization"] "")]
      (when-not (= header (str "Bearer " token))
        (error-response 401 "Unauthorized")))))

;; --- Repo resolution ---

(defn- lookup-repo-uri
  "Given a database name, look up the stored :repo/uri. Returns path string or nil."
  [db-dir db-name]
  (try
    (let [conn (db/get-or-create-conn db-dir db-name)
          db   (d/db conn)]
      (ffirst (d/q '[:find ?uri :where [_ :repo/uri ?uri]] db)))
    (catch Exception e
      (log! "lookup-repo-uri" db-name (.getMessage e))
      nil)))

(defn- resolve-repo
  "Resolve repo-path from request params, return context map or throw.
   Accepts a filesystem path or a database name (looks up :repo/uri)."
  [repo-path db-dir]
  (let [as-file   (io/file repo-path)
        valid?    (and (.exists as-file) (nil? (util/validate-repo-path (.getCanonicalPath as-file))))
        canonical (if valid?
                    (.getCanonicalPath as-file)
                    ;; Try as database name — look up stored repo/uri
                    (or (lookup-repo-uri db-dir repo-path)
                        (throw (ex-info (str "Repository not found: " repo-path
                                             ". Use a filesystem path or a database name from `noum databases`.")
                                        {:status 400
                                         :message (str "Repository not found: " repo-path
                                                       ". Use a filesystem path or a database name.")}))))]
    (when-let [reason (util/validate-repo-path canonical)]
      (throw (ex-info (str "Invalid repository: " reason " (" canonical ")")
                      {:status 400 :message (str "Invalid repository: " reason)})))
    (let [db-name (util/derive-db-name canonical)]
      {:repo-path canonical
       :db-dir    db-dir
       :db-name   db-name
       :conn      (db/get-or-create-conn db-dir db-name)
       :meta-conn (db/ensure-meta-db db-dir)})))

(defn- with-repo
  "Execute f with resolved repo context. Returns JSON response."
  [params db-dir f]
  (let [repo-path (:repo_path params)]
    (when-not repo-path
      (throw (ex-info "Missing repo_path" {:status 400 :message "repo_path is required"})))
    (let [ctx (resolve-repo repo-path db-dir)]
      (f (assoc ctx
                :db (d/db (:conn ctx))
                :meta-db (d/db (:meta-conn ctx)))))))

;; --- SSE streaming ---

(defn- sse-event [event data]
  (str "event: " event "\ndata: " (json/write-str data) "\n\n"))

(defn- wants-sse?
  "Check if the client requested SSE via Accept header."
  [request]
  (some-> (get-in request [:headers "accept"]) (str/includes? "text/event-stream")))

(defn- with-sse
  "Run body-fn in a future, streaming progress via SSE. body-fn receives a progress-fn.
   Sends the final result as an event and closes the channel."
  [request body-fn]
  (server/as-channel request
                     {:on-open
                      (fn [ch]
                        (server/send! ch {:status  200
                                          :headers {"Content-Type"  "text/event-stream"
                                                    "Cache-Control" "no-cache"
                                                    "Connection"    "keep-alive"}}
                                      false)
                        (future
                          (try
                            (let [progress-fn (fn [evt]
                                                (server/send! ch (sse-event "progress" evt) false))
                                  result (body-fn progress-fn)]
                              (server/send! ch (sse-event "result" result) false)
                              (server/send! ch (sse-event "done" {}) true))
                            (catch Exception e
                              (server/send! ch (sse-event "error" {:message (.getMessage e)}) true)))))}))

;; --- Shared helpers ---

(defn- resolve-provider
  "Resolve provider and model from request params with config defaults."
  [params config]
  {:provider (or (:provider params) (:provider config))
   :model    (or (:model params) (:model config))})

;; --- Route handlers ---

(defn- handle-health [_request config]
  (ok {:status "ok"
       :version (util/read-version)
       :uptime-ms (- (System/currentTimeMillis) (:started-at config 0))}))

(defn- run-import [{:keys [conn repo-path]} progress-fn]
  (let [git-r   (git/import-commits! conn repo-path repo-path progress-fn)
        files-r (files/import-files! conn repo-path repo-path)]
    {:commits-imported (:commits-imported git-r)
     :commits-skipped  (:commits-skipped git-r)
     :files-imported   (:files-imported files-r)
     :files-skipped    (:files-skipped files-r)
     :dirs-imported    (:dirs-imported files-r)}))

(defn- handle-import [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-import ctx))
          (ok (run-import ctx nil)))))))

(defn- run-analyze [{:keys [conn meta-db repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn model-id]}
        (llm/wrap-as-prompt-fn-from-opts (resolve-provider params config))
        concurrency (min (or (:concurrency params) 3) 20)
        result      (analyze/analyze-repo! conn repo-path prompt-fn
                                           (cond-> {:meta-db     meta-db
                                                    :model-id    model-id
                                                    :concurrency concurrency
                                                    :progress-fn progress-fn}
                                             (:max_files params)
                                             (assoc :max-files (:max_files params))))]
    (select-keys result [:files-analyzed :files-skipped
                         :files-errored :files-parse-errored
                         :total-usage])))

(defn- handle-analyze [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-analyze ctx params config))
          (ok (run-analyze ctx params config nil)))))))

(defn- run-enrich [{:keys [conn repo-path]} params progress-fn]
  (let [concurrency (min (or (:concurrency params) 8) 20)
        result      (imports/enrich-repo! conn repo-path {:concurrency concurrency
                                                          :progress-fn progress-fn})]
    (select-keys result [:files-processed :imports-resolved])))

(defn- handle-enrich [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-enrich ctx params))
          (ok (run-enrich ctx params nil)))))))

(defn- handle-update [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [{:keys [conn meta-db repo-path]}]
        (let [opts (if (:analyze params)
                     (let [{:keys [prompt-fn model-id]}
                           (llm/wrap-as-prompt-fn-from-opts
                            {:provider (:provider config)
                             :model    (:model config)})]
                       {:concurrency 8 :analyze? true
                        :meta-db meta-db :model-id model-id :invoke-llm prompt-fn})
                     {:concurrency 8})
              result (sync/update-repo! conn repo-path repo-path opts)]
          (ok result))))))

(defn- run-digest [{:keys [conn meta-db repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn model-id]}
        (llm/wrap-as-prompt-fn-from-opts (resolve-provider params config))
        step-progress (fn [step-name inner-fn]
                        (when progress-fn
                          (progress-fn {:current 0 :total 0 :message (str "digest: " step-name "...")}))
                        (inner-fn))
        update-r  (when-not (or (:skip_import params) (:skip_enrich params))
                    (step-progress "update"
                                   #(sync/update-repo! conn repo-path repo-path {:concurrency 8})))
        analyze-r (when-not (:skip_analyze params)
                    (step-progress "analyze"
                                   #(let [r (analyze/analyze-repo! conn repo-path prompt-fn
                                                                   {:meta-db meta-db :model-id model-id
                                                                    :concurrency 3 :progress-fn progress-fn})]
                                      (select-keys r [:files-analyzed :total-usage]))))
        bench-r   (when-not (:skip_benchmark params)
                    (step-progress "benchmark"
                                   #(let [db     (d/db conn)
                                          layers (when-let [ls (:layers params)]
                                                   (mapv keyword (str/split ls #",")))
                                          mode   (cond-> {} layers (assoc :layers layers))
                                          r      (bench/run-benchmark! db repo-path prompt-fn
                                                                       :meta-db meta-db :conn conn :mode mode
                                                                       :budget {:max-questions (:max_questions params)}
                                                                       :report? (:report params)
                                                                       :concurrency 3
                                                                       :progress-fn progress-fn)]
                                      (select-keys r [:run-id :aggregate :stop-reason :report-path]))))]
    (cond-> {}
      update-r  (assoc :update update-r)
      analyze-r (assoc :analyze analyze-r)
      bench-r   (assoc :benchmark bench-r))))

(defn- handle-digest [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-digest ctx params config))
          (ok (run-digest ctx params config nil)))))))

(defn- handle-ask [request config]
  (let [params (parse-json-body request)]
    (when-not (:question params)
      (throw (ex-info "Missing question" {:status 400 :message "question is required"})))
    (with-repo params (:db-dir config)
      (fn [{:keys [db meta-db db-name]}]
        (let [{:keys [invoke-fn]}
              (llm/make-messages-fn-from-opts
               (merge (resolve-provider params config)
                      {:temperature 0.3 :max-tokens 4096}))
              max-iter (min (or (:max_iterations params) 10) 50)
              result   (agent/ask meta-db db (:question params)
                                  {:invoke-fn      invoke-fn
                                   :repo-name      db-name
                                   :max-iterations max-iter
                                   :continue-from  (:continue_from params)})]
          (ok {:answer     (:answer result)
               :status     (:status result)
               :session-id (:session-id result)
               :usage      (:usage result)}))))))

(defn- handle-query-exec [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [{:keys [db meta-db]}]
        (let [query-name (:query_name params)
              raw-params (or (:params params) {})
              kw-params  (into {} (map (fn [[k v]] [(keyword (name k)) v])) raw-params)
              result     (query/run-named-query meta-db db query-name kw-params)]
          (if (:ok result)
            (let [rows  (:ok result)
                  limit (min (or (:limit params) 500) 10000)]
              (ok {:query   query-name
                   :total   (count rows)
                   :results (take limit rows)}))
            (error-response 400 (:error result))))))))

(defn- handle-queries [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))
        meta-db   (d/db meta-conn)
        queries   (->> (artifacts/list-active-query-names meta-db)
                       (keep (fn [n]
                               (when-let [q (artifacts/load-named-query meta-db n)]
                                 {:name        n
                                  :description (:description q)
                                  :inputs      (mapv name (:inputs q []))}))))]
    (ok queries)))

(defn- with-db-name
  "Connect by database name (not repo path). For GET endpoints where
   we only need the database, not a filesystem path."
  [db-name db-dir f]
  (let [conn    (db/get-or-create-conn db-dir db-name)
        meta-conn (db/ensure-meta-db db-dir)]
    (f {:conn conn :db (d/db conn)
        :meta-conn meta-conn :meta-db (d/db meta-conn)
        :db-name db-name})))

(defn- handle-schema [request config]
  (let [db-name (get-in request [:params :repo])]
    (with-db-name db-name (:db-dir config)
      (fn [{:keys [db]}]
        (ok {:schema (query/schema-summary db)})))))

(defn- handle-status [request config]
  (let [db-name (get-in request [:params :repo])]
    (with-db-name db-name (:db-dir config)
      (fn [{:keys [db]}]
        (ok (query/repo-stats db))))))

(defn- handle-databases [_request config]
  (let [db-dir (:db-dir config)
        names  (db/list-db-dirs db-dir)]
    (if (seq names)
      (let [client (db/create-client db-dir)
            stats  (->> names
                        (mapv #(db/db-stats client %))
                        (remove :error))]
        (ok stats))
      (ok []))))

(defn- handle-delete-database [request config]
  (let [db-name (get-in request [:params :name])
        db-dir  (:db-dir config)
        client  (db/create-client db-dir)]
    (db/delete-db client db-name)
    ;; Clean up the directory that Datomic Local leaves behind
    (let [db-path (io/file db-dir "noumenon" db-name)]
      (when (.isDirectory db-path)
        (run! io/delete-file (reverse (file-seq db-path)))))
    (ok {:deleted db-name})))

(defn- run-benchmark [{:keys [conn meta-db db repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn]}
        (llm/wrap-as-prompt-fn-from-opts (resolve-provider params config))
        layers (when-let [ls (:layers params)]
                 (mapv keyword (str/split ls #",")))
        mode   (cond-> {} layers (assoc :layers layers))
        result (bench/run-benchmark! db repo-path prompt-fn
                                     :meta-db meta-db :conn conn :mode mode
                                     :budget {:max-questions (:max_questions params)}
                                     :report? (:report params)
                                     :concurrency 3
                                     :progress-fn progress-fn)]
    (select-keys result [:run-id :aggregate :stop-reason :report-path])))

(defn- handle-benchmark-run [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-benchmark ctx params config))
          (ok (run-benchmark ctx params config nil)))))))

(defn- handle-benchmark-results [request config]
  (let [params   (merge (parse-json-body request)
                        (:query-params request))
        repo     (or (:repo_path params) (get-in request [:params :repo]))]
    (with-repo {:repo_path repo} (:db-dir config)
      (fn [{:keys [db]}]
        (let [run-id (:run_id params)
              runs   (if run-id
                       (d/q '[:find (pull ?r [*]) :in $ ?id
                              :where [?r :bench.run/id ?id]]
                            db run-id)
                       (d/q '[:find (pull ?r [*])
                              :where [?r :bench.run/id _]]
                            db))
              run    (->> runs (map first) (sort-by :bench.run/started-at) last)]
          (if run
            (ok run)
            (error-response 404 "No benchmark runs found.")))))))

(defn- handle-benchmark-compare [request config]
  (let [params (merge (parse-json-body request)
                      (:query-params request))
        repo   (or (:repo_path params) (get-in request [:params :repo]))]
    (with-repo {:repo_path repo} (:db-dir config)
      (fn [{:keys [db]}]
        (let [pull-run (fn [id]
                         (ffirst (d/q '[:find (pull ?r [*]) :in $ ?id
                                        :where [?r :bench.run/id ?id]]
                                      db id)))
              run-a (pull-run (:run_id_a params))
              run-b (pull-run (:run_id_b params))]
          (cond
            (not run-a) (error-response 404 (str "Run not found: " (:run_id_a params)))
            (not run-b) (error-response 404 (str "Run not found: " (:run_id_b params)))
            :else       (ok (bench/compare-runs run-a run-b))))))))

(defn- run-introspect [{:keys [db meta-conn db-name repo-path]} params config progress-fn]
  (let [{:keys [provider model]} (resolve-provider params config)
        {:keys [invoke-fn]}
        (llm/make-messages-fn-from-opts
         {:provider provider :model model :temperature 0.7 :max-tokens 8192})
        invoke-fn-factory
        (fn []
          (:invoke-fn
           (llm/make-messages-fn-from-opts
            {:provider provider :model model :temperature 0.0 :max-tokens 4096})))]
    (introspect/run-loop!
     (cond-> {:db db :repo-name db-name :repo-path repo-path
              :meta-conn meta-conn
              :invoke-fn-factory invoke-fn-factory
              :optimizer-invoke-fn invoke-fn
              :max-iterations (or (:max_iterations params) 10)
              :max-hours (:max_hours params)
              :max-cost (:max_cost params)
              :eval-runs (or (:eval_runs params) 1)
              :git-commit? (:git_commit params)
              :model-config {:provider provider :model model}
              :progress-fn progress-fn}
       (:target params)
       (assoc :allowed-targets
              (set (map keyword (str/split (:target params) #","))))))))

(defn- handle-introspect [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-introspect ctx params config))
          (ok (run-introspect ctx params config nil)))))))

(defn- handle-introspect-status [request _config]
  (let [params (merge (parse-json-body request) (:query-params request))
        run-id (:run_id params)]
    (if-let [session (sessions/get-session run-id)]
      (let [{:keys [status result error started-at]} session]
        (ok (cond-> {:status (name status)}
              (= :running status)
              (assoc :elapsed-min (quot (- (System/currentTimeMillis) (or started-at 0)) 60000))
              result (assoc :improvements (:improvements result)
                            :iterations (:iterations result)
                            :final-score (:final-score result))
              error (assoc :error error))))
      (error-response 404 (str "Unknown run ID: " run-id)))))

(defn- handle-introspect-stop [request _config]
  (let [params (merge (parse-json-body request) (:query-params request))
        run-id (:run_id params)]
    (if-let [session (sessions/get-session run-id)]
      (case (:status session)
        :running   (do (reset! (:stop-flag session) true)
                       (ok {:stopped run-id}))
        :completed (ok {:message (str "Run " run-id " already completed.")})
        :stopped   (ok {:message (str "Run " run-id " already stopped.")})
        :error     (ok {:message (str "Run " run-id " already terminated with an error.")}))
      (error-response 404 (str "Unknown run ID: " run-id)))))

(defn- handle-introspect-history [request config]
  (let [params     (merge (parse-json-body request) (:query-params request))
        query-name (or (:query_name params)
                       (get-in request [:params :query]))]
    (when-not (str/starts-with? (str query-name) "introspect-")
      (throw (ex-info "Only introspect-* queries" {:status 400 :message "Only introspect-* queries"})))
    (let [meta-conn (db/ensure-meta-db (:db-dir config))
          meta-db   (d/db meta-conn)
          result    (query/run-named-query meta-db meta-db query-name)]
      (if (:ok result)
        (ok {:query query-name :results (take 100 (:ok result))})
        (error-response 400 (:error result))))))

(defn- handle-reseed [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))]
    (artifacts/reseed! meta-conn)
    (ok {:queries (count (artifacts/list-active-query-names (d/db meta-conn)))})))

(defn- handle-artifact-history [request config]
  (let [params    (merge (parse-json-body request) (:query-params request))
        atype     (:type params)
        aname     (:name params)
        meta-conn (db/ensure-meta-db (:db-dir config))
        history   (case atype
                    "prompt" (artifacts/prompt-history meta-conn aname)
                    "rules"  (artifacts/rules-history meta-conn)
                    (throw (ex-info "Invalid type" {:status 400 :message "type must be 'prompt' or 'rules'"})))]
    (ok history)))

;; --- Routing ---

(defn- parse-path-params
  "Simple path param extraction. Pattern like '/api/status/:repo'
   matches '/api/status/myrepo' with {:repo 'myrepo'}."
  [pattern path]
  (let [pattern-parts (str/split pattern #"/")
        path-parts    (str/split path #"/")]
    (when (= (count pattern-parts) (count path-parts))
      (loop [pats pattern-parts
             vals path-parts
             params {}]
        (if (empty? pats)
          params
          (let [pat (first pats)
                val (first vals)]
            (cond
              (str/starts-with? pat ":") (recur (rest pats) (rest vals)
                                                (assoc params (keyword (subs pat 1)) val))
              (= pat val)                (recur (rest pats) (rest vals) params)
              :else                      nil)))))))

(def ^:private routes
  [[:get  "/health"                    handle-health]
   [:post "/api/import"                handle-import]
   [:post "/api/analyze"               handle-analyze]
   [:post "/api/enrich"                handle-enrich]
   [:post "/api/update"                handle-update]
   [:post "/api/digest"                handle-digest]
   [:post "/api/ask"                   handle-ask]
   [:post "/api/query"                 handle-query-exec]
   [:get  "/api/queries"               handle-queries]
   [:get  "/api/schema/:repo"          handle-schema]
   [:get  "/api/status/:repo"          handle-status]
   [:get  "/api/databases"             handle-databases]
   [:delete "/api/databases/:name"     handle-delete-database]
   [:post "/api/benchmark"             handle-benchmark-run]
   [:get  "/api/benchmark/results"     handle-benchmark-results]
   [:get  "/api/benchmark/compare"     handle-benchmark-compare]
   [:post "/api/introspect"            handle-introspect]
   [:get  "/api/introspect/status"     handle-introspect-status]
   [:post "/api/introspect/stop"       handle-introspect-stop]
   [:get  "/api/introspect/history"    handle-introspect-history]
   [:post "/api/reseed"                handle-reseed]
   [:get  "/api/artifacts/history"     handle-artifact-history]])

(defn- match-route [method path]
  (some (fn [[route-method pattern handler]]
          (when (= route-method method)
            (when-let [params (if (= pattern path)
                                {}
                                (parse-path-params pattern path))]
              {:handler handler :params params})))
        routes))

(defn- parse-query-params
  "Parse query string into a keyword map. Returns {} if no query string."
  [query-string]
  (if (or (nil? query-string) (str/blank? query-string))
    {}
    (->> (str/split query-string #"&")
         (map #(str/split % #"=" 2))
         (filter #(= 2 (count %)))
         (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode v "UTF-8")]))
         (into {}))))

;; --- Ring handler ---

(defn make-handler
  "Create the ring handler. Config map keys:
   :db-dir    - Datomic storage directory
   :provider  - Default LLM provider
   :model     - Default model alias
   :token     - Auth bearer token (nil = no auth)
   :started-at - Epoch ms when daemon started"
  [config]
  (fn [request]
    (let [method (keyword (str/lower-case (name (:request-method request))))
          path   (:uri request)
          qp     (parse-query-params (:query-string request))]
      (if-let [{:keys [handler params]} (match-route method path)]
        (let [request (assoc request
                             :params (merge (:params request) params)
                             :query-params qp)]
          (or (check-auth request (:token config))
              (try
                (handler request config)
                (catch clojure.lang.ExceptionInfo e
                  (let [data (ex-data e)]
                    (error-response (or (:status data) 500)
                                    (or (:message data) (.getMessage e)))))
                (catch Exception e
                  (log! "http/error" (.getMessage e))
                  (error-response 500 "Internal server error")))))
        (error-response 404 "Not found")))))

;; --- Server lifecycle ---

(defonce ^:private server-atom (atom nil))

(defn- write-daemon-file!
  "Write daemon.edn with port, pid, and start time. Owner-only permissions."
  [port]
  (let [home-dir (io/file (System/getProperty "user.home") ".noumenon")
        daemon-file (io/file home-dir "daemon.edn")]
    (.mkdirs home-dir)
    (spit daemon-file
          (pr-str {:port       port
                   :pid        (.pid (ProcessHandle/current))
                   :started-at (System/currentTimeMillis)}))
    ;; Owner-only read/write (600) — file may contain connection info
    (try
      (.setReadable daemon-file false false)
      (.setWritable daemon-file false false)
      (.setReadable daemon-file true false)
      (.setWritable daemon-file true false)
      (catch Exception _))))

(defn- delete-daemon-file! []
  (let [f (io/file (System/getProperty "user.home") ".noumenon" "daemon.edn")]
    (when (.exists f) (.delete f))))

(defn start!
  "Start the HTTP daemon on the given port (0 = auto-assign).
   Returns the actual port."
  [{:keys [port bind db-dir provider model token]
    :or   {port 0 bind "127.0.0.1"}}]
  (when @server-atom
    (throw (ex-info "Daemon already running" {})))
  ;; Resolve token from arg or env var (env avoids process-list leaks)
  (let [resolved-token (or token (System/getenv "NOUMENON_TOKEN"))
        ;; Refuse to bind to non-localhost without auth
        _ (when (and (not= bind "127.0.0.1") (not resolved-token))
            (throw (ex-info (str "Cannot bind to " bind " without --token or NOUMENON_TOKEN. "
                                 "Remote access requires authentication.")
                            {})))
        config     {:db-dir     (or db-dir (util/resolve-db-dir {}))
                    :provider   provider
                    :model      model
                    :token      resolved-token
                    :started-at (System/currentTimeMillis)}
        handler    (make-handler config)
        srv         (server/run-server handler {:ip bind :port port})
        actual-port (or (some-> srv meta :local-port)
                        port)]
    (reset! server-atom srv)
    (write-daemon-file! actual-port)
    (when (not= bind "127.0.0.1")
      (log! "WARNING: Daemon is network-accessible. Use TLS (reverse proxy) for production."))
    (log! (str "Noumenon daemon listening on " bind ":" actual-port))
    actual-port))

(defn stop! []
  (when-let [srv @server-atom]
    (srv)
    (reset! server-atom nil)
    (delete-daemon-file!)
    (log! "Noumenon daemon stopped")))
