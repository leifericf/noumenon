(ns noumenon.http.middleware
  "Cross-cutting infrastructure shared by every HTTP handler — input
   validation, JSON framing, bearer-token auth, repo resolution, SSE
   streaming, and CORS. Handler clusters depend on this namespace, not
   on each other."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.auth :as auth]
            [noumenon.db :as db]
            [noumenon.query :as query]
            [noumenon.repo :as repo]
            [noumenon.util :as util :refer [log!]]
            [org.httpkit.server :as server])
  (:import [java.security MessageDigest]))

;; --- Length caps ---

(def max-param-value-len util/max-param-value-len)
(def max-question-len    util/max-question-len)
(def max-run-id-len      util/max-run-id-len)
(def max-branch-name-len util/max-branch-name-len)
(def max-host-len        util/max-host-len)
(def max-db-name-len     util/max-db-name-len)
(def max-query-name-len  util/max-query-name-len)
(def max-layers-len 64)

(def allowed-layers #{:raw :import :enrich :full :embedded})
(def allowed-introspect-targets #{:examples :system-prompt :rules :code :train})

(def validate-string-length! util/validate-string-length!)

(defn validate-query-params!
  "Reject query parameter map shapes that exceed shared limits."
  [kw-params]
  (util/validate-params! kw-params))

;; clamp-limit lives in noumenon.query so CLI and HTTP can share one
;; definition. Re-exported here so handlers don't need a separate require.
(def clamp-limit query/clamp-limit)

(def ^:private max-exclude-paths 1000)

(defn validate-exclude-paths!
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

(defn validate-layers
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

(defn validate-db-name!
  "Reject db-name values that aren't filesystem-safe identifiers.
   Throws ExceptionInfo with status 400 on invalid names."
  [db-name]
  (when (or (str/blank? db-name)
            (not (re-matches #"[a-zA-Z0-9._-]+" db-name))
            (re-matches #"\.+" db-name))
    (throw (ex-info "Invalid database name"
                    {:status 400 :message "Invalid database name"}))))

;; --- JSON framing ---

(defn json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json"
             "Cache-Control" "no-cache"}
   :body    (json/write-str body)})

(defn ok [data]
  (json-response 200 {:ok true :data data}))

(defn error-response [status message]
  (json-response status {:ok false :error message}))

(defn parse-json-body [request]
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

(defn check-auth
  "Validate bearer token and check role for the request.
   Returns nil if auth passes, or an error response map.
   Supports both the bootstrap admin token (env var) and Datomic-stored tokens."
  [request {:keys [token meta-conn]} method path]
  (if (or (not token) (= path "/health"))
    nil
    (let [bearer (extract-bearer request)]
      (cond
        (str/blank? bearer)
        (error-response 401 "Unauthorized — bearer token required")

        (constant-time= bearer token)
        nil

        :else
        (let [valid (try
                      (when meta-conn
                        (auth/validate-token (d/db meta-conn) bearer))
                      (catch Exception _ ::lookup-failed))]
          (cond
            (= valid ::lookup-failed)
            (error-response 401 "Unauthorized — invalid or revoked token")

            (some? valid)
            (when (and (= (:role valid) :reader) (auth/requires-admin? method path))
              (error-response 403 "Forbidden — admin token required for this operation"))

            :else
            (error-response 401 "Unauthorized — invalid or revoked token")))))))

;; --- Repo resolution ---

(defn lookup-repo-uri
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

(defn resolve-repo
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

(defn with-repo
  "Execute f with resolved repo context. Returns JSON response.
   Type-checks, length-caps, and rejects blank `repo_path` up front so
   non-string / oversized / empty values surface as 400 rather than
   leaking ClassCastException as 500 inside `resolve-repo`'s FS code
   path."
  [params db-dir f]
  (let [repo-path (:repo_path params)]
    (when-not repo-path
      (throw (ex-info "Missing repo_path" {:status 400 :message "repo_path is required"})))
    (util/validate-repo-path-input! repo-path)
    (let [ctx (resolve-repo repo-path db-dir)]
      (f (assoc ctx
                :db (d/db (:conn ctx))
                :meta-db (d/db (:meta-conn ctx)))))))

(defn with-imported-repo
  "Like `with-repo`, but rejects with 404 when the resolved DB has not
   been imported yet. Use for endpoints that require a populated graph
   (enrich, analyze, synthesize) — without this guard,
   `db/get-or-create-conn` silently creates an empty Datomic DB and
   the operation reports zero work, leaving a phantom database
   on disk. Endpoints that *establish* the DB (import, update, digest)
   keep using `with-repo`. The on-disk check runs BEFORE the conn is
   created so the guard cannot itself create the directory it's
   guarding against."
  [params db-dir f]
  (let [repo-path (:repo_path params)]
    (when-not repo-path
      (throw (ex-info "Missing repo_path" {:status 400 :message "repo_path is required"})))
    (util/validate-repo-path-input! repo-path)
    (let [{:keys [db-name]}
          (repo/resolve-repo repo-path db-dir {:lookup-uri-fn lookup-repo-uri})
          db-path (io/file db-dir "noumenon" db-name)]
      (when-not (.isDirectory db-path)
        (let [msg (str "Database not yet imported for " db-name
                       ". Run /api/import first.")]
          (throw (ex-info msg {:status 404 :message msg}))))
      (with-repo params db-dir f))))

(defn with-db-name
  "Connect by database name (not repo path). For GET endpoints where
   we only need the database, not a filesystem path."
  [db-name db-dir f]
  (validate-db-name! db-name)
  (let [db-path (io/file db-dir "noumenon" db-name)]
    (when-not (.isDirectory db-path)
      (throw (ex-info (str "Database not found: " db-name
                           ". Use `noum databases` to see available databases.")
                      {:status 404 :message (str "Database not found: " db-name)})))
    (let [conn      (db/get-or-create-conn db-dir db-name)
          meta-conn (db/ensure-meta-db db-dir)]
      (f {:conn conn :db (d/db conn)
          :meta-conn meta-conn :meta-db (d/db meta-conn)
          :db-name db-name}))))

;; --- SSE / CORS ---

(defn- sse-event [event data]
  (str "event: " event "\ndata: " (json/write-str data) "\n\n"))

(defn wants-sse?
  "Check if the client requested SSE via Accept header."
  [request]
  (some-> (get-in request [:headers "accept"]) (str/includes? "text/event-stream")))

(def ^:private cors-headers
  {"Access-Control-Allow-Methods" "GET, POST, DELETE, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type, Accept, Authorization"})

(defn allowed-origin?
  "Only allow localhost origins. file:// requires NOUMENON_ALLOW_FILE_ORIGIN=true."
  [origin]
  (when origin
    (or (re-matches #"https?://localhost(:\d+)?" origin)
        (re-matches #"https?://127\.0\.0\.1(:\d+)?" origin)
        (when (System/getenv "NOUMENON_ALLOW_FILE_ORIGIN")
          (or (= "file://" origin)
              (str/starts-with? origin "file://"))))))

(defn with-cors
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

(defn with-sse
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

;; --- Provider/model resolution ---

(defn resolve-provider
  "Resolve provider and model from request params with config defaults."
  [params config]
  {:provider (or (:provider params) (:provider config))
   :model    (or (:model params) (:model config))})
