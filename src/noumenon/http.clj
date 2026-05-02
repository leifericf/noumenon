(ns noumenon.http
  "HTTP API for the Noumenon daemon. Serves REST-ish JSON endpoints
   on localhost for the `noum` CLI launcher and future Electron UI."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [org.httpkit.server :as server]
            [noumenon.auth :as auth]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.benchmark :as bench]
            [noumenon.db :as db]
            [noumenon.delta :as delta]
            [noumenon.embed :as embed]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.imports :as imports]
            [noumenon.introspect :as introspect]
            [noumenon.ask-store :as ask-store]
            [noumenon.llm :as llm]
            [noumenon.query :as query]
            [noumenon.sessions :as sessions]
            [noumenon.sync :as sync]
            [noumenon.synthesize :as synthesize]
            [noumenon.concurrency :as cc]
            [noumenon.repo :as repo]
            [noumenon.repo-manager :as repo-mgr]
            [noumenon.util :as util :refer [log!]])
  (:import [java.lang ProcessHandle]
           [java.security MessageDigest]))

;; --- Input validation ---
;; Length caps and the params validator live in noumenon.util so the
;; MCP layer (which talks to the same handlers) shares the exact same
;; thresholds. HTTP-specific validators (CORS-style ones, exclude_paths
;; cardinality) stay here.

(def ^:private max-param-value-len util/max-param-value-len)
(def ^:private max-question-len    util/max-question-len)
(def ^:private max-run-id-len      util/max-run-id-len)
(def ^:private max-branch-name-len util/max-branch-name-len)
(def ^:private max-host-len        util/max-host-len)
(def ^:private max-db-name-len     util/max-db-name-len)
(def ^:private max-query-name-len  util/max-query-name-len)
(def ^:private max-layers-len 64)
(def ^:private allowed-layers #{:raw :import :enrich :full :embedded})
(def ^:private allowed-introspect-targets #{:examples :system-prompt :rules :code :train})

(def ^:private validate-string-length! util/validate-string-length!)

(defn- validate-query-params!
  "Reject query parameter map shapes that exceed shared limits."
  [kw-params]
  (util/validate-params! kw-params))

(def ^:private max-exclude-paths 1000)

(defn- validate-exclude-paths!
  "Validate the :exclude_paths parameter for federation queries.
   Accepts nil/missing, an empty seq, or a vector of strings.
   Returns the (possibly empty) vector of paths."
  [v]
  (cond
    (nil? v) []
    (not (sequential? v))
    (throw (ex-info "exclude_paths must be an array of strings"
                    {:status 400 :message "exclude_paths must be an array of strings"}))
    (> (count v) max-exclude-paths)
    (throw (ex-info (str "exclude_paths exceeds max " max-exclude-paths)
                    {:status 400 :message (str "exclude_paths exceeds max " max-exclude-paths)}))
    :else
    (mapv (fn [p]
            (when-not (string? p)
              (throw (ex-info "exclude_paths entries must be strings"
                              {:status 400 :message "exclude_paths entries must be strings"})))
            (validate-string-length! "exclude_paths" p max-param-value-len)
            p) v)))

(defn- validate-layers
  "Parse and validate a comma-separated layers string. Returns keyword vector or nil."
  [layers-str]
  (when layers-str
    (validate-string-length! "layers" layers-str max-layers-len)
    (let [kws (mapv keyword (str/split layers-str #","))]
      (when-let [bad (seq (remove allowed-layers kws))]
        (throw (ex-info (str "Unknown layers: " (pr-str bad))
                        {:status 400
                         :message (str "Unknown layers: " (pr-str bad)
                                       ". Valid: raw, import, enrich, full")})))
      kws)))

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
        (try
          (json/read-str s :key-fn keyword)
          (catch Exception e
            (log! "http/parse-json" (.getMessage e))
            (throw (ex-info "Invalid JSON body"
                            {:status 400 :message "Invalid JSON body"}))))))))

;; --- Auth ---

(defn- constant-time=
  "Constant-time string comparison to prevent timing attacks.
   Hashes both inputs first to ensure fixed-length comparison."
  [a b]
  (let [digest #(.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (str %) "UTF-8"))]
    (MessageDigest/isEqual (digest a) (digest b))))

(defn- extract-bearer [request]
  (some-> (get-in request [:headers "authorization"] "")
          (str/replace #"^Bearer\s+" "")))

(defn- check-auth
  "Validate bearer token and check role for the request.
   Returns nil if auth passes, or an error response map.
   Supports both the bootstrap admin token (env var) and Datomic-stored tokens."
  [request {:keys [token meta-conn]} method path]
  (if (or (not token) (= path "/health"))
    nil ;; no auth configured, or health check (always public)
    (let [bearer (extract-bearer request)]
      (cond
        (str/blank? bearer)
        (error-response 401 "Unauthorized — bearer token required")

        ;; Bootstrap admin token — always admin role
        (constant-time= bearer token)
        nil

        ;; Check Datomic-stored tokens
        :else
        (if-let [{:keys [role]} (when meta-conn
                                  (auth/validate-token
                                   (datomic.client.api/db meta-conn) bearer))]
          (when (and (= role :reader) (auth/requires-admin? method path))
            (error-response 403 "Forbidden — admin token required for this operation"))
          (error-response 401 "Unauthorized — invalid or revoked token"))))))

;; --- Repo resolution ---

(defn- lookup-repo-uri
  "Given a database name, look up the stored :repo/uri. Returns path string or nil.
   Only connects if the database already exists — never creates one."
  [db-dir db-name]
  (let [db-path (io/file db-dir "noumenon" db-name)]
    (when (.isDirectory db-path)
      (try
        (let [conn (db/get-or-create-conn db-dir db-name)
              db   (d/db conn)]
          (ffirst (d/q '[:find ?uri :where [_ :repo/uri ?uri]] db)))
        (catch Exception e
          (log! "lookup-repo-uri" db-name (.getMessage e))
          nil)))))

(defn- resolve-repo
  "Resolve repo identifier from request params, return context map or throw.
   Accepts a filesystem path, Git URL, or database name."
  [identifier db-dir]
  (try
    (let [{:keys [repo-path db-name]}
          (repo/resolve-repo identifier db-dir {:lookup-uri-fn lookup-repo-uri})]
      {:repo-path repo-path
       :db-dir    db-dir
       :db-name   db-name
       :conn      (db/get-or-create-conn db-dir db-name)
       :meta-conn (db/ensure-meta-db db-dir)})
    (catch clojure.lang.ExceptionInfo e
      (let [msg    (.getMessage e)
            status (if (re-find #"(?i)not found" msg) 404 400)]
        (throw (ex-info msg (assoc (ex-data e) :status status :message msg)))))))

(defn- with-repo
  "Execute f with resolved repo context. Returns JSON response.
   Type-checks, length-caps, and rejects blank `repo_path` up front so
   non-string / oversized / empty values surface as 400 rather than
   leaking ClassCastException as 500 inside `resolve-repo`'s FS code
   path. The full FS-shape validator stays specific to handlers that
   require a real filesystem path (e.g. delta-ensure)."
  [params db-dir f]
  (let [repo-path (:repo_path params)]
    (when-not repo-path
      (throw (ex-info "Missing repo_path" {:status 400 :message "repo_path is required"})))
    (util/validate-repo-path-input! repo-path)
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

(def ^:private cors-headers
  {"Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Accept, Authorization"})

(defn- allowed-origin?
  "Only allow localhost origins. file:// requires NOUMENON_ALLOW_FILE_ORIGIN=true."
  [origin]
  (when origin
    (or (re-matches #"https?://localhost(:\d+)?" origin)
        (re-matches #"https?://127\.0\.0\.1(:\d+)?" origin)
        (when (System/getenv "NOUMENON_ALLOW_FILE_ORIGIN")
          (or (= "file://" origin)
              (str/starts-with? origin "file://"))))))

(defn- with-cors
  "Add CORS headers to a response, restricting origin to localhost."
  ([response] (with-cors response nil))
  ([response request]
   (let [origin (some-> request (get-in [:headers "origin"]))
         origin-header (if (allowed-origin? origin)
                         origin
                         "http://localhost")]
     (update response :headers merge
             (assoc cors-headers
                    "Access-Control-Allow-Origin" origin-header
                    "X-Content-Type-Options" "nosniff"
                    "X-Frame-Options" "DENY"
                    "Referrer-Policy" "no-referrer"
                    "Content-Security-Policy" "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'")))))

(defn- with-sse
  "Run body-fn in a future, streaming progress via SSE. body-fn receives a progress-fn.
   Sends the final result as an event and closes the channel.
   Rejects cross-origin requests before starting expensive work."
  [request body-fn]
  (let [origin (get-in request [:headers "origin"])]
    (if (and origin (not (allowed-origin? origin)))
      (error-response 403 "Origin not allowed")
      (server/as-channel request
                         {:on-open
                          (fn [ch]
                            (let [origin-h (or origin "http://localhost")]
                              (server/send! ch {:status  200
                                                :headers (merge cors-headers
                                                                {"Content-Type"  "text/event-stream"
                                                                 "Cache-Control" "no-cache"
                                                                 "Connection"    "keep-alive"
                                                                 "Access-Control-Allow-Origin" origin-h})}
                                            false)
                              (future
                                (try
                                  (let [progress-fn (fn [evt]
                                                      (server/send! ch (sse-event "progress" evt) false))
                                        result (body-fn progress-fn)]
                                    (server/send! ch (sse-event "result" result) false)
                                    (server/send! ch (sse-event "done" {}) true))
                                  (catch clojure.lang.ExceptionInfo e
                                    (log! "sse/error" (.getMessage e))
                                    (server/send! ch (sse-event "error" {:message (.getMessage e)}) true))
                                  (catch Exception e
                                    (log! "sse/error" (.getMessage e))
                                    (server/send! ch (sse-event "error" {:message "Internal server error"}) true))))))}))))

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
  (let [{:keys [prompt-fn model-id provider-kw]}
        (llm/wrap-as-prompt-fn-from-opts (resolve-provider params config))
        concurrency (min (or (:concurrency params) 3) 20)
        selector    (select-keys params [:path :include :exclude :lang])
        result      (analyze/analyze-repo! conn repo-path prompt-fn
                                           (cond-> (assoc selector
                                                          :meta-db meta-db
                                                          :model-id model-id
                                                          :provider (name provider-kw)
                                                          :concurrency concurrency
                                                          :no-promote? (boolean (:no_promote params))
                                                          :progress-fn progress-fn)
                                             (:max_files params)
                                             (assoc :max-files (:max_files params))))]
    (select-keys result [:files-analyzed :files-promoted :files-skipped
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
        selector    (select-keys params [:path :include :exclude :lang])
        result      (imports/enrich-repo! conn repo-path (assoc selector
                                                                :concurrency concurrency
                                                                :progress-fn progress-fn))]
    (select-keys result [:files-processed :imports-resolved])))

(defn- handle-enrich [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-enrich ctx params))
          (ok (run-enrich ctx params nil)))))))

(defn- handle-delta-ensure [request _config]
  (let [params    (parse-json-body request)
        repo-path (:repo_path params)
        basis-sha (:basis_sha params)
        branch    (:branch params)]
    (when-not (and repo-path basis-sha)
      (throw (ex-info "Missing repo_path or basis_sha"
                      {:status 400 :message "repo_path and basis_sha are required"})))
    (when-not (sync/valid-sha? basis-sha)
      (throw (ex-info "Invalid basis_sha"
                      {:status 400 :message "basis_sha must be a 40-char lowercase hex SHA"})))
    (when-let [reason (util/validate-repo-path repo-path)]
      (throw (ex-info reason {:status 400 :message (str "repo_path " reason)})))
    (validate-string-length! "branch" branch max-branch-name-len)
    (validate-string-length! "parent_host" (:parent_host params) max-host-len)
    (validate-string-length! "parent_db_name" (:parent_db_name params) max-db-name-len)
    (let [delta-opts (cond-> {}
                       branch                     (assoc :branch-name branch)
                       (:parent_host params)      (assoc :parent-host (:parent_host params))
                       (:parent_db_name params)   (assoc :parent-db-name (:parent_db_name params)))
          conn       (delta/ensure-delta-db! repo-path basis-sha delta-opts)
          result     (delta/update-delta! conn repo-path basis-sha delta-opts)]
      (ok result))))

(defn- handle-update [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [{:keys [conn meta-db repo-path]}]
        (let [opts (if (:analyze params)
                     (let [{:keys [prompt-fn model-id provider-kw]}
                           (llm/wrap-as-prompt-fn-from-opts
                            (resolve-provider params config))]
                       (assoc (select-keys params [:path :include :exclude :lang])
                              :concurrency 8 :analyze? true
                              :meta-db meta-db :model-id model-id
                              :provider (name provider-kw) :invoke-llm prompt-fn))
                     (assoc (select-keys params [:path :include :exclude :lang])
                            :concurrency 8))
              result (sync/update-repo! conn repo-path repo-path opts)]
          (ok result))))))

(defn- run-synthesize [{:keys [conn meta-db db-name]} params config progress-fn]
  (let [{:keys [prompt-fn model-id provider-kw]}
        (llm/wrap-as-prompt-fn-from-opts (resolve-provider params config))]
    (when progress-fn (progress-fn {:current 0 :total 0 :message "Synthesizing architecture..."}))
    (synthesize/synthesize-repo! conn prompt-fn
                                 {:meta-db meta-db :provider (name provider-kw)
                                  :model-id model-id :repo-name db-name})))

(defn- handle-synthesize [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-synthesize ctx params config))
          (ok (run-synthesize ctx params config nil)))))))

(defn- run-digest [{:keys [conn meta-db db-dir db-name repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn model-id provider-kw]}
        (llm/wrap-as-prompt-fn-from-opts (resolve-provider params config))
        step-progress (fn [step-name inner-fn]
                        (when progress-fn
                          (progress-fn {:current 0 :total 0 :message (str "digest: " step-name "...")}))
                        (inner-fn))
        step-fn     (fn [step-name]
                      (when progress-fn
                        (fn [evt] (progress-fn (assoc evt :step step-name)))))
        selector  (select-keys params [:path :include :exclude :lang])
        update-r  (when-not (or (:skip_import params) (:skip_enrich params))
                    (step-progress "update"
                                   #(sync/update-repo! conn repo-path repo-path
                                                       (assoc selector :concurrency 8))))
        analyze-r (when-not (:skip_analyze params)
                    (step-progress "analyze"
                                   #(let [r (analyze/analyze-repo! conn repo-path prompt-fn
                                                                   (assoc selector
                                                                          :meta-db meta-db :model-id model-id
                                                                          :provider (name provider-kw)
                                                                          :concurrency 3
                                                                          :no-promote? (boolean (:no_promote params))
                                                                          :progress-fn (step-fn "analyze")))]
                                      (select-keys r [:files-analyzed :files-promoted :total-usage]))))
        synth-r   (when-not (:skip_synthesize params)
                    (step-progress "synthesize"
                                   #(synthesize/synthesize-repo!
                                     conn prompt-fn
                                     {:meta-db meta-db :provider (name provider-kw) :model-id model-id
                                      :repo-name (last (str/split repo-path #"/"))})))
        bench-r   (when-not (:skip_benchmark params)
                    (step-progress "benchmark"
                                   #(let [db     (d/db conn)
                                          layers (validate-layers (:layers params))
                                          mode   (cond-> {} layers (assoc :layers layers))
                                          r      (bench/run-benchmark! db repo-path prompt-fn
                                                                       :meta-db meta-db :conn conn :mode mode
                                                                       :budget {:max-questions (:max_questions params)}
                                                                       :report? (:report params)
                                                                       :concurrency 3
                                                                       :progress-fn (step-fn "benchmark")
                                                                       :db-dir db-dir :db-name db-name)]
                                      (select-keys r [:run-id :aggregate :stop-reason :report-path]))))]
    (cond-> {}
      update-r  (assoc :update update-r)
      analyze-r (assoc :analyze analyze-r)
      synth-r   (assoc :synthesize synth-r)
      bench-r   (assoc :benchmark bench-r))))

(defn- handle-digest [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-digest ctx params config))
          (ok (run-digest ctx params config nil)))))))

(defn- run-ask [{:keys [db meta-db meta-conn db-dir db-name]} params config progress-fn]
  (let [{:keys [invoke-fn]}
        (llm/make-messages-fn-from-opts
         (merge (resolve-provider params config)
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

(defn- handle-ask [request config]
  (let [params (parse-json-body request)]
    (when (or (nil? (:question params))
              (and (string? (:question params))
                   (str/blank? (:question params))))
      (throw (ex-info "Missing question" {:status 400 :message "question is required"})))
    (validate-string-length! "question" (:question params) max-question-len)
    (with-repo params (:db-dir config)
      (fn [ctx]
        (if (wants-sse? request)
          (with-sse request (partial run-ask ctx params config))
          (ok (run-ask ctx params config nil)))))))

(defn- handle-query-exec [request config]
  (let [params (parse-json-body request)]
    (validate-string-length! "query_name" (:query_name params) max-query-name-len)
    (with-repo params (:db-dir config)
      (fn [{:keys [db meta-db]}]
        (let [query-name    (:query_name params)
              raw-params    (or (:params params) {})
              kw-params     (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) raw-params)
              _             (validate-query-params! kw-params)
              exclude-paths (validate-exclude-paths! (:exclude_paths params))
              result        (query/run-named-query meta-db db query-name kw-params
                                                   {:exclude-paths exclude-paths})]
          (if (:ok result)
            (let [rows  (:ok result)
                  limit (min (or (some-> (:limit params) str parse-long) 500) 10000)]
              (ok {:query            query-name
                   :total            (count rows)
                   :federation-safe? (:federation-safe? result)
                   :results          (take limit rows)}))
            (error-response 400 (:error result))))))))

(defn- handle-query-raw [request config]
  (let [params (parse-json-body request)]
    (with-repo params (:db-dir config)
      (fn [{:keys [db]}]
        (let [query-edn (:query params)
              limit     (min (or (some-> (:limit params) str parse-long) 500) 10000)]
          (when-not query-edn
            (throw (ex-info "Missing query" {:status 400 :message "query is required (EDN string)"})))
          (validate-string-length! "query" query-edn max-param-value-len)
          ;; Raw queries are not federation-safe in v1: column types are unknown,
          ;; so we can't guarantee the launcher can row-merge with delta DB rows.
          ;; The :exclude_paths parameter is accepted but ignored; the response
          ;; surfaces :federation-safe? false so the launcher falls back to trunk-only.
          (validate-exclude-paths! (:exclude_paths params))
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
            (ok {:total            (count results)
                 :federation-safe? false
                 :results          (take limit (vec results))})))))))

(defn- handle-query-as-of [request config]
  (let [params    (parse-json-body request)
        as-of-str (:as_of params)]
    (validate-string-length! "query_name" (:query_name params) max-query-name-len)
    (when-not as-of-str
      (throw (ex-info "Missing as_of" {:status 400 :message "as_of is required (ISO-8601 or epoch ms)"})))
    (when-not (or (string? as-of-str) (number? as-of-str))
      ;; Reject vectors / maps / booleans up front so the catch below
      ;; doesn't leak the JVM ClassCastException class names into the
      ;; 400 response body. The string-but-unparseable branch (e.g.
      ;; "not-a-date") keeps the underlying Instant/parse message so
      ;; users see the actual complaint about their date string.
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
      (with-repo params (:db-dir config)
        (fn [{:keys [conn meta-db]}]
          (let [query-name    (:query_name params)
                raw-params    (or (:params params) {})
                kw-params     (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) raw-params)
                _             (validate-query-params! kw-params)
                exclude-paths (validate-exclude-paths! (:exclude_paths params))
                limit         (min (or (some-> (:limit params) str parse-long) 500) 10000)
                db            (d/as-of (d/db conn) as-of-inst)
                result        (query/run-named-query meta-db db query-name kw-params
                                                     {:exclude-paths exclude-paths})]
            (if (:ok result)
              (let [rows (:ok result)]
                (ok {:query            query-name
                     :as-of            (str as-of-inst)
                     :total            (count rows)
                     :federation-safe? (:federation-safe? result)
                     :results          (take limit rows)}))
              (error-response 400 (:error result)))))))))

(defn- federated-delta-opts
  "Build opts for `delta/update-delta!` from a federated-query request.
   Auto-derives :parent-host from the request's Host header and
   :parent-db-name from the resolved trunk DB so the delta's branch
   entity carries the lineage breadcrumb without the caller having to
   pass them explicitly. Without this, only `delta-ensure` callers got
   parent metadata; auto-federated callers (the launcher's plain
   `noum query` rewrite) silently lost it."
  [request trunk-db-name branch]
  (cond-> {:parent-db-name trunk-db-name}
    branch                              (assoc :branch-name branch)
    (get-in request [:headers "host"])  (assoc :parent-host
                                               (get-in request [:headers "host"]))))

(defn- handle-query-federated
  "Run a named query over both trunk and a local delta DB, then concatenate
   delta rows on top of trunk rows. Trunk is queried with :exclude-paths
   covering every :file/path in the delta, so a path appears in at most one
   side of the merge.

   Federation requires the query to be marked :federation-safe? in its EDN.
   Non-federation-safe queries return trunk-only with :federation-safe? false."
  [request config]
  (let [params      (parse-json-body request)
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
    (validate-string-length! "query_name" query-name max-query-name-len)
    (validate-string-length! "branch" branch max-branch-name-len)
    (when-not (sync/valid-sha? basis-sha)
      (throw (ex-info "Invalid basis_sha"
                      {:status 400 :message "basis_sha must be a 40-char lowercase hex SHA"})))
    (when-let [reason (util/validate-repo-path repo-path)]
      (throw (ex-info reason {:status 400 :message (str "repo_path " reason)})))
    (validate-query-params! kw-params)
    (with-repo {:repo_path repo-path} (:db-dir config)
      (fn [{:keys [db meta-db db-name]}]
        (let [delta-opts (federated-delta-opts request db-name branch)
              delta-conn (delta/ensure-delta-db! repo-path basis-sha delta-opts)
              _          (delta/update-delta! delta-conn repo-path basis-sha delta-opts)
              delta-db   (d/db delta-conn)
              limit      (min (or (some-> (:limit params) str parse-long) 500) 10000)
              result     (query/run-federated-query meta-db db delta-db query-name kw-params)]
          (if (:error result)
            (error-response 400 (:error result))
            (ok {:query            query-name
                 :total            (count (:ok result))
                 :trunk-count      (:trunk-count result)
                 :delta-count      (:delta-count result)
                 :basis-sha        basis-sha
                 :federation-safe? (:federation-safe? result)
                 :results          (take limit (:ok result))})))))))

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

(defn- validate-db-name!
  "Reject db-name values that aren't filesystem-safe identifiers.
   Throws ExceptionInfo with status 400 on invalid names. The positive
   allowlist `[a-zA-Z0-9._-]+` matches what `util/derive-db-name`
   produces, so every legitimate db-name (canonical user repos and the
   synthesized delta DBs) passes; control bytes, slashes, spaces, and
   non-ASCII are rejected at the boundary. Pure-dot names (`.`, `..`,
   `...`) match the allowlist syntactically but resolve to dir names
   that traverse upward, so they get the explicit dot-only rejection."
  [db-name]
  (when (or (str/blank? db-name)
            (not (re-matches #"[a-zA-Z0-9._-]+" db-name))
            (re-matches #"\.+" db-name))
    (throw (ex-info "Invalid database name"
                    {:status 400 :message "Invalid database name"}))))

(defn- with-db-name
  "Connect by database name (not repo path). For GET endpoints where
   we only need the database, not a filesystem path."
  [db-name db-dir f]
  (validate-db-name! db-name)
  (let [db-path (io/file db-dir "noumenon" db-name)]
    (when-not (.isDirectory db-path)
      (throw (ex-info (str "Database not found: " db-name
                           ". Use `noum databases` to see available databases.")
                      {:status 404 :message (str "Database not found: " db-name)})))
    (let [conn    (db/get-or-create-conn db-dir db-name)
          meta-conn (db/ensure-meta-db db-dir)]
      (f {:conn conn :db (d/db conn)
          :meta-conn meta-conn :meta-db (d/db meta-conn)
          :db-name db-name}))))

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
        _       (validate-db-name! db-name)
        _       (when (= db-name db/meta-db-name)
                  (throw (ex-info (str "Cannot delete reserved database: " db-name)
                                  {:status 400
                                   :message (str "Cannot delete reserved database: " db-name)})))
        db-dir  (:db-dir config)
        db-path (io/file db-dir "noumenon" db-name)]
    (if (.isDirectory db-path)
      (do (let [client (db/create-client db-dir)]
            (db/delete-db client db-name))
          (db/evict-conn! db-dir db-name)
          (ok {:deleted db-name}))
      (error-response 404 (str "Database not found: " db-name)))))

(defn- run-benchmark [{:keys [conn meta-db db db-dir db-name repo-path]} params config progress-fn]
  (let [{:keys [prompt-fn]}
        (llm/wrap-as-prompt-fn-from-opts (resolve-provider params config))
        layers (validate-layers (:layers params))
        mode   (cond-> {} layers (assoc :layers layers))
        result (bench/run-benchmark! db repo-path prompt-fn
                                     :meta-db meta-db :conn conn :mode mode
                                     :budget {:max-questions (:max_questions params)}
                                     :report? (:report params)
                                     :concurrency 3
                                     :progress-fn progress-fn
                                     :db-dir db-dir :db-name db-name)]
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
              _      (when run-id (validate-string-length! "run_id" run-id max-run-id-len))
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
        (let [_       (validate-string-length! "run_id_a" (:run_id_a params) max-run-id-len)
              _       (validate-string-length! "run_id_b" (:run_id_b params) max-run-id-len)
              pull-run (fn [id]
                         (ffirst (d/q '[:find (pull ?r [*]) :in $ ?id
                                        :where [?r :bench.run/id ?id]]
                                      db id)))
              run-a (pull-run (:run_id_a params))
              run-b (pull-run (:run_id_b params))]
          (cond
            (not run-a) (error-response 404 (str "Run not found: " (:run_id_a params)))
            (not run-b) (error-response 404 (str "Run not found: " (:run_id_b params)))
            :else       (ok (bench/compare-runs run-a run-b))))))))

(defn- resolve-extra-repos
  "Resolve comma-separated repo identifiers to [{:db db :repo-name name} ...]."
  [extra-repos-str db-dir]
  (when (seq extra-repos-str)
    (->> (str/split extra-repos-str #",")
         (mapv (fn [raw]
                 (let [raw     (str/trim raw)
                       {:keys [db-name conn]}
                       (resolve-repo raw db-dir)]
                   {:db (d/db conn) :repo-name db-name}))))))

(defn- build-introspect-opts [{:keys [db meta-conn db-name db-dir repo-path]} params config
                              {:keys [stop-flag run-id progress-fn]}]
  (let [{:keys [provider model]} (resolve-provider params config)
        {:keys [invoke-fn]}
        (llm/make-messages-fn-from-opts
         {:provider provider :model model :temperature 0.7 :max-tokens 8192})
        invoke-fn-factory
        (fn []
          (:invoke-fn
           (llm/make-messages-fn-from-opts
            {:provider provider :model model :temperature 0.0 :max-tokens 4096})))
        extra-repos (resolve-extra-repos (:extra_repos params) db-dir)]
    (cond-> {:db db :repo-name db-name :repo-path repo-path
             :meta-conn meta-conn
             :invoke-fn-factory invoke-fn-factory
             :optimizer-invoke-fn invoke-fn
             :max-iterations (or (:max_iterations params) 10)
             :max-hours (:max_hours params)
             :max-cost (:max_cost params)
             :eval-runs (or (:eval_runs params) 1)
             :git-commit? (and (:git_commit params)
                               (not (:read-only config))
                               (not (git/bare-repo? repo-path)))
             :model-config {:provider provider :model model}
             :progress-fn progress-fn}
      stop-flag          (assoc :stop-flag stop-flag)
      run-id             (assoc :run-id run-id)
      (seq extra-repos)  (assoc :extra-repos extra-repos)
      (:target params)
      (assoc :allowed-targets
             (->> (str/split (:target params) #",")
                  (keep (comp allowed-introspect-targets keyword str/trim))
                  set)))))

(defn- handle-introspect [request config]
  (let [params (parse-json-body request)]
    (sessions/evict-stale!)
    (when (>= (sessions/running-count) sessions/max-sessions)
      (throw (ex-info "Too many active introspect sessions"
                      {:status 429
                       :message (str "Maximum " sessions/max-sessions
                                     " concurrent sessions. Stop one first.")})))
    (with-repo params (:db-dir config)
      (fn [ctx]
        (let [stop-flag (atom false)
              run-id    (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID))
              now       (System/currentTimeMillis)
              run-opts  (build-introspect-opts ctx params config
                                               {:stop-flag stop-flag :run-id run-id})]
          (when (>= (sessions/running-count) sessions/max-sessions)
            (throw (ex-info "Too many active introspect sessions"
                            {:status 429
                             :message (str "Maximum " sessions/max-sessions
                                           " concurrent sessions. Stop one first.")})))
          (sessions/register! run-id {:status :running :stop-flag stop-flag :started-at now})
          (if (wants-sse? request)
            (with-sse request
              (fn [progress-fn]
                (try
                  (let [result (introspect/run-loop! (assoc run-opts :progress-fn progress-fn))
                        final  (if @stop-flag :stopped :completed)]
                    (sessions/update-session!
                     run-id #(merge % {:status final :result result
                                       :completed-at (System/currentTimeMillis)}))
                    (assoc result :run-id run-id))
                  (catch Exception e
                    (sessions/update-session!
                     run-id #(merge % {:status :error :error (.getMessage e)
                                       :completed-at (System/currentTimeMillis)}))
                    (throw e)))))
            (try
              (let [result (introspect/run-loop! run-opts)
                    final  (if @stop-flag :stopped :completed)]
                (sessions/update-session!
                 run-id #(merge % {:status final :result result
                                   :completed-at (System/currentTimeMillis)}))
                (ok (assoc result :run-id run-id)))
              (catch Exception e
                (sessions/update-session!
                 run-id #(merge % {:status :error :error (.getMessage e)
                                   :completed-at (System/currentTimeMillis)}))
                (throw e)))))))))

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
    (validate-string-length! "query_name" (str query-name) max-run-id-len)
    (when-not (or (str/starts-with? (str query-name) "introspect-")
                  (str/starts-with? (str query-name) "ask-"))
      (throw (ex-info "Only introspect-* and ask-* queries"
                      {:status 400 :message "Only introspect-* and ask-* queries"})))
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

;; --- Ask sessions ---

(defn- handle-ask-sessions [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))
        sessions  (ask-store/list-sessions (d/db meta-conn))]
    (ok sessions)))

(defn- handle-ask-session-detail [request config]
  (let [params     (merge (parse-json-body request) (:query-params request))
        session-id (or (:session_id params) (get-in request [:params :id]))]
    (when-not session-id
      (throw (ex-info "Missing session_id" {:status 400 :message "session_id is required"})))
    (let [meta-conn (db/ensure-meta-db (:db-dir config))
          session   (ask-store/get-session (d/db meta-conn) session-id)]
      (if session
        (ok session)
        (error-response 404 "Session not found")))))

(defn- handle-ask-session-feedback [request config]
  (let [params     (parse-json-body request)
        session-id (or (:session_id params) (get-in request [:params :id]))
        _          (when-not (:feedback params)
                     (throw (ex-info "Missing feedback" {:status 400 :message "feedback field is required ('positive' or 'negative')"})))
        polarity   (keyword (:feedback params))
        comment    (or (:comment params) "")]
    (when-not session-id
      (throw (ex-info "Missing session_id" {:status 400 :message "session_id is required"})))
    (when-not (#{:positive :negative} polarity)
      (throw (ex-info "Invalid feedback polarity" {:status 400 :message "feedback must be 'positive' or 'negative'"})))
    (validate-string-length! "session_id" session-id max-run-id-len)
    (validate-string-length! "comment" comment max-param-value-len)
    (let [meta-conn (db/ensure-meta-db (:db-dir config))]
      (if (ask-store/get-session (d/db meta-conn) session-id)
        (do (ask-store/set-feedback! meta-conn session-id polarity comment)
            (ok {:session-id session-id}))
        (error-response 404 "Session not found")))))

;; --- Token management (admin only — enforced by role middleware) ---

(defn- handle-token-create [request config]
  (let [params (parse-json-body request)
        role   (some-> (:role params) keyword)
        label  (:label params)]
    (when-not (#{:reader :admin} role)
      (throw (ex-info "role must be 'reader' or 'admin'"
                      {:status 400 :message "role must be 'reader' or 'admin'"})))
    (when label (validate-string-length! "label" label 256))
    (let [result (auth/create-token! (:meta-conn config)
                                     {:role role :label (or label "")})]
      (json-response 201 {:ok true :data (assoc result :label (or label ""))}))))

(defn- handle-token-list [_request config]
  (ok (auth/list-tokens (d/db (:meta-conn config)))))

(defn- handle-token-revoke [request config]
  (let [id-str (get-in request [:params :id])]
    (when-not id-str
      (throw (ex-info "Missing token id" {:status 400 :message "token id is required"})))
    (let [id (try (java.util.UUID/fromString id-str)
                  (catch Exception _
                    (throw (ex-info "Invalid UUID" {:status 400 :message "Invalid token id"}))))]
      (if (auth/revoke-token! (:meta-conn config) id)
        (ok {:revoked id-str})
        (error-response 404 "Token not found")))))

;; --- Repo management (admin only) ---

(defn- handle-repo-register [request config]
  (let [params (parse-json-body request)
        url    (:url params)]
    (when-not url
      (throw (ex-info "url is required" {:status 400 :message "url is required"})))
    (validate-string-length! "url" url max-param-value-len)
    (when-let [n (:name params)] (validate-string-length! "name" n 256))
    (ok (repo-mgr/register-repo! (:meta-conn config) (:db-dir config)
                                 {:url    url
                                  :name   (:name params)
                                  :branch (:branch params)}))))

(defn- handle-repo-list [_request config]
  (let [repos  (repo-mgr/registered-repos (d/db (:meta-conn config)))
        db-dir (:db-dir config)]
    (ok (mapv (fn [{:keys [db-name] :as r}]
                (let [clone (repo-mgr/repo-clone-path db-dir db-name)
                      sha   (try (git/head-sha clone) (catch Exception _ nil))]
                  (assoc r :head-sha sha)))
              repos))))

(defn- registered-repo!
  "Look up a registered repo by db-name in the meta DB; throw a 404
   ex-info if it isn't registered. Shared by remove/refresh so callers
   surface a clean 'Repo not registered' rather than 500'ing on the
   downstream Datomic null-deref or 'Clone not found' fs error."
  [config name]
  (when-not name
    (throw (ex-info "Missing repo name" {:status 400 :message "repo name is required"})))
  (let [meta-conn (or (:meta-conn config) (db/ensure-meta-db (:db-dir config)))
        meta-db   (d/db meta-conn)]
    (or (some #(when (= name (:db-name %)) %) (repo-mgr/registered-repos meta-db))
        (throw (ex-info (str "Repo not registered: " name)
                        {:status 404 :message (str "Repo not registered: " name)})))))

(defn- handle-repo-remove [request config]
  (let [name (get-in request [:params :name])]
    (registered-repo! config name)
    (repo-mgr/remove-repo! (:meta-conn config) (:db-dir config) name)
    (ok {:removed name})))

(defn- handle-repo-refresh [request config]
  (let [name (get-in request [:params :name])]
    (registered-repo! config name)
    (ok (repo-mgr/refresh-repo! (:db-dir config) name))))

;; Cache completion source data per db-name (invalidated on import/enrich)
(defonce ^:private completion-cache (atom {}))

(defn- get-completion-data
  "Load or return cached completion source data for a repo."
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
               {:data data :ttl (+ (System/currentTimeMillis) 60000)}) ;; 60s TTL
        data))))

(defn- handle-completions [request config]
  (let [params (:query-params request)
        prefix (or (:prefix params) "")
        repo   (:repo_path params)]
    (when-not repo
      (throw (ex-info "Missing repo_path" {:status 400 :message "repo_path is required"})))
    (with-repo {:repo_path repo} (:db-dir config)
      (fn [{:keys [db meta-db db-name]}]
        (let [src       (get-completion-data db meta-db db-name)
              lc-prefix (.toLowerCase ^String prefix)
              ;; All filtering is in-memory against cached data
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
                       ;; Most specific matches first
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
          (ok (vec (take 15 items))))))))

;; --- Settings ---

(defn- handle-settings-get [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))
        settings  (artifacts/get-all-settings (d/db meta-conn))]
    (ok settings)))

(defn- handle-settings-post [request config]
  (let [params    (parse-json-body request)
        key       (:key params)
        value     (:value params)]
    (when-not (and key (some? value))
      (throw (ex-info "Missing key or value" {:status 400 :message "key and value are required"})))
    (validate-string-length! "key" key 256)
    (when (string? value)
      (validate-string-length! "value" value max-param-value-len))
    (let [meta-conn (db/ensure-meta-db (:db-dir config))
          edn-value (if (string? value)
                      (try (edn/read-string {:readers {}} value) (catch Exception _ value))
                      value)]
      (artifacts/set-setting! meta-conn key edn-value)
      (ok {:key key}))))

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
        (if-not (seq pats)
          params
          (let [pat (first pats)
                val (first vals)]
            (cond
              (str/starts-with? pat ":") (recur (rest pats) (rest vals)
                                                (assoc params (keyword (subs pat 1))
                                                       (java.net.URLDecoder/decode val "UTF-8")))
              (= pat val)                (recur (rest pats) (rest vals) params)
              :else                      nil)))))))

(def ^:private routes
  [[:get  "/health"                    handle-health]
   [:post "/api/import"                handle-import]
   [:post "/api/analyze"               handle-analyze]
   [:post "/api/enrich"                handle-enrich]
   [:post "/api/update"                handle-update]
   [:post "/api/delta/ensure"          handle-delta-ensure]
   [:post "/api/synthesize"            handle-synthesize]
   [:post "/api/digest"                handle-digest]
   [:post "/api/ask"                   handle-ask]
   [:post "/api/query"                 handle-query-exec]
   [:post "/api/query-raw"             handle-query-raw]
   [:post "/api/query-as-of"           handle-query-as-of]
   [:post "/api/query-federated"       handle-query-federated]
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
   [:get  "/api/artifacts/history"     handle-artifact-history]
   [:get  "/api/completions"           handle-completions]
   [:get  "/api/settings"              handle-settings-get]
   [:post "/api/settings"              handle-settings-post]
   [:get  "/api/ask/sessions"          handle-ask-sessions]
   [:get  "/api/ask/sessions/:id"      handle-ask-session-detail]
   [:post "/api/ask/sessions/:id/feedback" handle-ask-session-feedback]
   [:post "/api/tokens"                 handle-token-create]
   [:get  "/api/tokens"                 handle-token-list]
   [:delete "/api/tokens/:id"           handle-token-revoke]
   [:post "/api/repos"                  handle-repo-register]
   [:get  "/api/repos"                  handle-repo-list]
   [:delete "/api/repos/:name"          handle-repo-remove]
   [:post "/api/repos/:name/refresh"    handle-repo-refresh]])

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
   :db-dir      - Datomic storage directory
   :provider    - Default LLM provider
   :model       - Default model alias
   :token       - Bootstrap admin bearer token (nil = no auth)
   :meta-conn   - Meta database connection (for token storage)
   :read-only   - When true, reject all mutating requests
   :started-at  - Epoch ms when daemon started"
  [config]
  (fn [request]
    (let [method (keyword (str/lower-case (name (:request-method request))))
          path   (:uri request)
          qp     (parse-query-params (:query-string request))]
      ;; Handle CORS preflight
      (if (= method :options)
        (with-cors {:status 204 :headers {} :body nil} request)
        (with-cors
          (if-let [{:keys [handler params]} (match-route method path)]
            (let [request (assoc request
                                 :params (merge (:params request) params)
                                 :query-params qp)]
              (or (check-auth request config method path)
                  (when (and (:read-only config)
                             (auth/writes-data? method path))
                    (error-response 503 "Server is in read-only mode"))
                  (try
                    (handler request config)
                    (catch clojure.lang.ExceptionInfo e
                      (let [data (ex-data e)
                            status (or (:status data) 500)]
                        (when (>= status 500)
                          (log! "http/error" (.getMessage e)))
                        (error-response status
                                        (if (>= status 500)
                                          "Internal server error"
                                          (or (:message data) "Internal server error")))))
                    (catch Exception e
                      (log! "http/error" (.getMessage e))
                      (error-response 500 "Internal server error")))))
            (error-response 404 "Not found"))
          request)))))

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
      (let [ok? (and (.setReadable daemon-file false false)
                     (.setWritable daemon-file false false)
                     (.setReadable daemon-file true false)
                     (.setWritable daemon-file true false))]
        (when-not ok?
          (log! "daemon/warn" "Could not set owner-only permissions on daemon.edn")))
      (catch Exception _
        (log! "daemon/warn" "Could not set permissions on daemon.edn")))))

(defn- delete-daemon-file! []
  (let [f (io/file (System/getProperty "user.home") ".noumenon" "daemon.edn")]
    (when (.exists f) (.delete f))))

(defn- resolve-server-config
  "Merge CLI flags with env-var fallbacks. CLI flags take precedence."
  [{:keys [port bind db-dir provider model token read-only
           max-ask-sessions max-llm-concurrency log-format
           webhook-secret poll-interval]}]
  (let [resolved-token   (not-empty (or token (util/env "NOUMENON_TOKEN")))
        resolved-bind    (or bind (System/getenv "NOUMENON_BIND") "127.0.0.1")
        resolved-port    (or port (util/env-int "NOUMENON_PORT") 0)]
    {:port               resolved-port
     :bind               resolved-bind
     :db-dir             (or db-dir (System/getenv "NOUMENON_DB_DIR")
                             (util/resolve-db-dir {}))
     :provider           (or provider (System/getenv "NOUMENON_LLM_PROVIDER"))
     :model              (or model (System/getenv "NOUMENON_LLM_MODEL"))
     :token              resolved-token
     :read-only          (or read-only (util/env-bool "NOUMENON_READ_ONLY"))
     :max-ask-sessions   (or max-ask-sessions (util/env-int "NOUMENON_MAX_ASK_SESSIONS") 50)
     :max-llm-concurrency (or max-llm-concurrency (util/env-int "NOUMENON_MAX_LLM_CONCURRENCY") 10)
     :log-format         (or log-format (System/getenv "NOUMENON_LOG_FORMAT") "text")
     :webhook-secret     (or webhook-secret (util/env "NOUMENON_WEBHOOK_SECRET"))
     :poll-interval      (or poll-interval (util/env-int "NOUMENON_POLL_INTERVAL") 5)
     :started-at         (System/currentTimeMillis)}))

(defn start!
  "Start the HTTP daemon on the given port (0 = auto-assign).
   Returns the actual port."
  [opts]
  (when @server-atom
    (throw (ex-info "Daemon already running" {})))
  (let [{:keys [bind token] :as config} (resolve-server-config opts)
        ;; Refuse to bind to non-localhost without auth
        _ (when (and (not= bind "127.0.0.1") (str/blank? token))
            (throw (ex-info (str "Cannot bind to " bind " without --token or NOUMENON_TOKEN. "
                                 "Remote access requires authentication.")
                            {})))
        meta-conn   (db/ensure-meta-db (:db-dir config))
        _           (cc/init-llm-semaphore! (:max-llm-concurrency config))
        config      (assoc config :meta-conn meta-conn)
        handler     (make-handler config)
        srv         (server/run-server handler {:ip bind :port (:port config)})
        actual-port (or (some-> srv meta :local-port)
                        (:port config))]
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
