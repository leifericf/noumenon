(ns noumenon.http.handlers.admin
  "Administrative HTTP handlers — health, ask sessions, token CRUD, repo
   registry, settings, database listing/deletion, reseed, and artifact
   history."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.ask-store :as ask-store]
            [noumenon.auth :as auth]
            [noumenon.db :as db]
            [noumenon.git :as git]
            [noumenon.http.middleware :as mw]
            [noumenon.repo-manager :as repo-mgr]
            [noumenon.util :as util]))

(defn handle-health [_request config]
  (mw/ok {:status "ok"
          :version (util/read-version)
          :uptime-ms (- (System/currentTimeMillis) (:started-at config 0))}))

(defn handle-databases [_request config]
  (let [db-dir (:db-dir config)
        names  (db/list-db-dirs db-dir)]
    (if (seq names)
      (let [client (db/create-client db-dir)
            stats  (->> names
                        (mapv #(db/db-stats client %))
                        (remove :error))]
        (mw/ok stats))
      (mw/ok []))))

(defn handle-delete-database [request config]
  (let [db-name (get-in request [:params :name])
        _       (mw/validate-db-name! db-name)
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
          (mw/ok {:deleted db-name}))
      (mw/error-response 404 (str "Database not found: " db-name)))))

(defn handle-reseed [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))]
    (artifacts/reseed! meta-conn)
    (mw/ok {:queries (count (artifacts/list-active-query-names (d/db meta-conn)))})))

(defn handle-artifact-history [request config]
  (let [params (merge (mw/parse-json-body request) (:query-params request))
        atype  (:type params)
        aname  (:name params)]
    (when-not (#{"prompt" "rules"} atype)
      (throw (ex-info "Invalid type"
                      {:status 400 :message "type must be 'prompt' or 'rules'"})))
    (when (and (= atype "prompt") (or (nil? aname) (str/blank? aname)))
      (throw (ex-info "Missing name"
                      {:status 400
                       :message "name is required when type is 'prompt'"})))
    (let [meta-conn (db/ensure-meta-db (:db-dir config))
          history   (case atype
                      "prompt" (do (mw/validate-string-length! "name" aname 256)
                                   (artifacts/prompt-history meta-conn aname))
                      "rules"  (artifacts/rules-history meta-conn))]
      (mw/ok history))))

;; --- Ask sessions ---

(defn handle-ask-sessions [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))
        sessions  (ask-store/list-sessions (d/db meta-conn))]
    (mw/ok sessions)))

(defn handle-ask-session-detail [request config]
  (let [params     (merge (mw/parse-json-body request) (:query-params request))
        session-id (or (:session_id params) (get-in request [:params :id]))]
    (when-not session-id
      (throw (ex-info "Missing session_id" {:status 400 :message "session_id is required"})))
    (let [meta-conn (db/ensure-meta-db (:db-dir config))
          session   (ask-store/get-session (d/db meta-conn) session-id)]
      (if session
        (mw/ok session)
        (mw/error-response 404 "Session not found")))))

(defn handle-ask-session-feedback [request config]
  (let [params     (mw/parse-json-body request)
        session-id (or (:session_id params) (get-in request [:params :id]))
        _          (when-not (:feedback params)
                     (throw (ex-info "Missing feedback" {:status 400 :message "feedback field is required ('positive' or 'negative')"})))
        polarity   (keyword (:feedback params))
        comment    (or (:comment params) "")]
    (when-not session-id
      (throw (ex-info "Missing session_id" {:status 400 :message "session_id is required"})))
    (when-not (#{:positive :negative} polarity)
      (throw (ex-info "Invalid feedback polarity" {:status 400 :message "feedback must be 'positive' or 'negative'"})))
    (mw/validate-string-length! "session_id" session-id mw/max-run-id-len)
    (mw/validate-string-length! "comment" comment mw/max-param-value-len)
    (let [meta-conn (db/ensure-meta-db (:db-dir config))]
      (if (ask-store/get-session (d/db meta-conn) session-id)
        (do (ask-store/set-feedback! meta-conn session-id polarity comment)
            (mw/ok {:session-id session-id}))
        (mw/error-response 404 "Session not found")))))

;; --- Token management (admin only — enforced by role middleware) ---

(defn handle-token-create [request config]
  (let [params (mw/parse-json-body request)
        role   (some-> (:role params) keyword)
        label  (:label params)]
    (when-not (#{:reader :admin} role)
      (throw (ex-info "role must be 'reader' or 'admin'"
                      {:status 400 :message "role must be 'reader' or 'admin'"})))
    (when label (mw/validate-string-length! "label" label 256))
    (let [result (auth/create-token! (:meta-conn config)
                                     {:role role :label (or label "")})]
      (mw/json-response 201 {:ok true :data (assoc result :label (or label ""))}))))

(defn handle-token-list [_request config]
  (mw/ok (auth/list-tokens (d/db (:meta-conn config)))))

(defn handle-token-revoke [request config]
  (let [id-str (get-in request [:params :id])]
    (when-not id-str
      (throw (ex-info "Missing token id" {:status 400 :message "token id is required"})))
    (let [id (try (java.util.UUID/fromString id-str)
                  (catch Exception _
                    (throw (ex-info "Invalid UUID" {:status 400 :message "Invalid token id"}))))]
      (if (auth/revoke-token! (:meta-conn config) id)
        (mw/ok {:revoked id-str})
        (mw/error-response 404 "Token not found")))))

;; --- Repo management (admin only) ---

(defn handle-repo-register [request config]
  (let [params (mw/parse-json-body request)
        url    (:url params)]
    (when-not url
      (throw (ex-info "url is required" {:status 400 :message "url is required"})))
    (mw/validate-string-length! "url" url mw/max-param-value-len)
    (when-let [n (:name params)] (mw/validate-string-length! "name" n 256))
    (mw/ok (repo-mgr/register-repo! (:meta-conn config) (:db-dir config)
                                    {:url    url
                                     :name   (:name params)
                                     :branch (:branch params)}))))

(defn handle-repo-list [_request config]
  (let [repos  (repo-mgr/registered-repos (d/db (:meta-conn config)))
        db-dir (:db-dir config)]
    (mw/ok (mapv (fn [{:keys [db-name] :as r}]
                   (let [clone (repo-mgr/repo-clone-path db-dir db-name)
                         sha   (try (git/head-sha clone) (catch Exception _ nil))]
                     (assoc r :head-sha sha)))
                 repos))))

(defn- registered-repo!
  "Look up a registered repo by db-name in the meta DB; throw a 404
   ex-info if it isn't registered."
  [config name]
  (when-not name
    (throw (ex-info "Missing repo name" {:status 400 :message "repo name is required"})))
  (let [meta-conn (or (:meta-conn config) (db/ensure-meta-db (:db-dir config)))
        meta-db   (d/db meta-conn)]
    (or (some #(when (= name (:db-name %)) %) (repo-mgr/registered-repos meta-db))
        (throw (ex-info (str "Repo not registered: " name)
                        {:status 404 :message (str "Repo not registered: " name)})))))

(defn handle-repo-remove [request config]
  (let [name (get-in request [:params :name])]
    (registered-repo! config name)
    (repo-mgr/remove-repo! (:meta-conn config) (:db-dir config) name)
    (mw/ok {:removed name})))

(defn handle-repo-refresh [request config]
  (let [name (get-in request [:params :name])]
    (registered-repo! config name)
    (mw/ok (repo-mgr/refresh-repo! (:db-dir config) name))))

;; --- Settings ---

(defn handle-settings-get [_request config]
  (let [meta-conn (db/ensure-meta-db (:db-dir config))
        settings  (artifacts/get-all-settings (d/db meta-conn))]
    (mw/ok settings)))

(defn handle-settings-post [request config]
  (let [params    (mw/parse-json-body request)
        key       (:key params)
        value     (:value params)]
    (when-not (and key (some? value))
      (throw (ex-info "Missing key or value" {:status 400 :message "key and value are required"})))
    (mw/validate-string-length! "key" key 256)
    (when (string? value)
      (mw/validate-string-length! "value" value mw/max-param-value-len))
    (let [meta-conn (db/ensure-meta-db (:db-dir config))]
      (artifacts/set-setting! meta-conn key value)
      (mw/ok {:key key}))))
