(ns noumenon.http.routes
  "Route table and ring entry point. Maps method+path to handler symbols
   sourced from the per-cluster handler namespaces; auth, CORS, and
   error translation are applied around every dispatched call."
  (:require [clojure.string :as str]
            [noumenon.auth :as auth]
            [noumenon.http.handlers.admin :as h-admin]
            [noumenon.http.handlers.benchmark :as h-bench]
            [noumenon.http.handlers.introspect :as h-intro]
            [noumenon.http.handlers.pipeline :as h-pipe]
            [noumenon.http.handlers.query :as h-query]
            [noumenon.http.middleware :as mw]
            [noumenon.util :refer [log!]]))

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
  [[:get  "/health"                       h-admin/handle-health]
   [:post "/api/import"                   h-pipe/handle-import]
   [:post "/api/analyze"                  h-pipe/handle-analyze]
   [:post "/api/enrich"                   h-pipe/handle-enrich]
   [:post "/api/update"                   h-pipe/handle-update]
   [:post "/api/delta/ensure"             h-pipe/handle-delta-ensure]
   [:post "/api/synthesize"               h-pipe/handle-synthesize]
   [:post "/api/digest"                   h-pipe/handle-digest]
   [:post "/api/ask"                      h-query/handle-ask]
   [:post "/api/query"                    h-query/handle-query-exec]
   [:post "/api/query-raw"                h-query/handle-query-raw]
   [:post "/api/query-as-of"              h-query/handle-query-as-of]
   [:post "/api/query-federated"          h-query/handle-query-federated]
   [:get  "/api/queries"                  h-query/handle-queries]
   [:get  "/api/schema/:repo"             h-query/handle-schema]
   [:get  "/api/status/:repo"             h-query/handle-status]
   [:get  "/api/databases"                h-admin/handle-databases]
   [:delete "/api/databases/:name"        h-admin/handle-delete-database]
   [:post "/api/benchmark"                h-bench/handle-benchmark-run]
   [:get  "/api/benchmark/results"        h-bench/handle-benchmark-results]
   [:get  "/api/benchmark/compare"        h-bench/handle-benchmark-compare]
   [:post "/api/introspect"               h-intro/handle-introspect]
   [:get  "/api/introspect/status"        h-intro/handle-introspect-status]
   [:post "/api/introspect/stop"          h-intro/handle-introspect-stop]
   [:get  "/api/introspect/history"       h-intro/handle-introspect-history]
   [:post "/api/reseed"                   h-admin/handle-reseed]
   [:get  "/api/artifacts/history"        h-admin/handle-artifact-history]
   [:get  "/api/completions"              h-query/handle-completions]
   [:get  "/api/settings"                 h-admin/handle-settings-get]
   [:post "/api/settings"                 h-admin/handle-settings-post]
   [:get  "/api/ask/sessions"             h-admin/handle-ask-sessions]
   [:get  "/api/ask/sessions/:id"         h-admin/handle-ask-session-detail]
   [:post "/api/ask/sessions/:id/feedback" h-admin/handle-ask-session-feedback]
   [:post "/api/tokens"                   h-admin/handle-token-create]
   [:get  "/api/tokens"                   h-admin/handle-token-list]
   [:delete "/api/tokens/:id"             h-admin/handle-token-revoke]
   [:post "/api/repos"                    h-admin/handle-repo-register]
   [:get  "/api/repos"                    h-admin/handle-repo-list]
   [:delete "/api/repos/:name"            h-admin/handle-repo-remove]
   [:post "/api/repos/:name/refresh"      h-admin/handle-repo-refresh]])

(defn- match-route [method path]
  (some (fn [[route-method pattern handler]]
          (when (= route-method method)
            (when-let [params (if (= pattern path)
                                {}
                                (parse-path-params pattern path))]
              {:handler handler :params params})))
        routes))

(defn- parse-query-params
  "Parse query string into a keyword map. Returns {} if no query string.
   Throws ex-info with `:status 400` on duplicate keys — silently
   collapsing repeated values via `(into {} …)` lets a request like
   `?x=safe&x=evil` slip the second value past any defender that
   filtered on the first occurrence (HTTP parameter pollution)."
  [query-string]
  (if (or (nil? query-string) (str/blank? query-string))
    {}
    (let [pairs (->> (str/split query-string #"&")
                     (map #(str/split % #"=" 2))
                     (filter #(= 2 (count %)))
                     (mapv (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode v "UTF-8")])))
          ks    (mapv first pairs)
          dup   (some (fn [[k n]] (when (> n 1) k)) (frequencies ks))]
      (when dup
        (let [field (name dup)
              msg   (str "duplicate query parameter " field)]
          (throw (ex-info msg
                          {:status 400 :message msg :user-message msg
                           :field field}))))
      (into {} pairs))))

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
          qp     (try
                   (parse-query-params (:query-string request))
                   (catch clojure.lang.ExceptionInfo e
                     {::parse-error e}))]
      (cond
        (= method :options)
        (mw/with-cors {:status 204 :headers {} :body nil} request)

        (::parse-error qp)
        (let [e    (::parse-error qp)
              data (ex-data e)]
          (mw/with-cors
            (mw/error-response (or (:status data) 400)
                               (or (:message data) "Bad request"))
            request))

        :else
        (mw/with-cors
          (if-let [{:keys [handler params]} (match-route method path)]
            (let [request (assoc request
                                 :params (merge (:params request) params)
                                 :query-params qp)]
              (or (mw/check-auth request config method path)
                  (when (and (:read-only config)
                             (auth/writes-data? method path))
                    (mw/error-response 503 "Server is in read-only mode"))
                  (try
                    (handler request config)
                    (catch clojure.lang.ExceptionInfo e
                      (let [data (ex-data e)
                            status (or (:status data) 500)]
                        (when (>= status 500)
                          (log! "http/error" (.getMessage e)))
                        (mw/error-response status
                                           (if (>= status 500)
                                             "Internal server error"
                                             (or (:message data) "Internal server error")))))
                    (catch Exception e
                      (log! "http/error" (.getMessage e))
                      (mw/error-response 500 "Internal server error")))))
            (mw/error-response 404 "Not found"))
          request)))))
