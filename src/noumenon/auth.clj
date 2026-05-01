(ns noumenon.auth
  "Token-based authentication and role-based access control.
   Tokens are stored in the meta database (noumenon-internal)."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.util :as util])
  (:import [java.security SecureRandom]
           [java.util UUID]))

;; --- Token generation ---

(def ^:private token-prefix "noum_")
(def ^:private token-bytes 32)
(def ^:private base62-chars
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")

(defn- random-base62 [n]
  (let [rng (SecureRandom.)
        buf (StringBuilder. n)]
    (dotimes [_ n]
      (.append buf (.charAt base62-chars (.nextInt rng (count base62-chars)))))
    (str buf)))

(defn- generate-token []
  (str token-prefix (random-base62 (* 2 token-bytes))))

(defn- hash-token [raw-token]
  (util/sha256-hex raw-token))

;; --- CRUD ---

(defn create-token!
  "Create a new token. Returns {:id uuid :token raw-value :role kw :label str}.
   The raw token value is returned exactly once — it is never stored."
  [meta-conn {:keys [role label]}]
  {:pre [(#{:reader :admin} role)]}
  (let [raw   (generate-token)
        id    (UUID/randomUUID)
        hash  (hash-token raw)]
    (d/transact meta-conn
                {:tx-data [{:token/id         id
                            :token/hash       hash
                            :token/role       role
                            :token/label      (or label "")
                            :token/created-at (java.util.Date.)
                            :token/revoked?   false}]})
    {:id id :token raw :role role :label (or label "")}))

(defn- token-entity
  "Look up a token entity by hash. Returns nil if not found."
  [db hash]
  (ffirst (d/q '[:find (pull ?t [:token/id :token/role :token/label
                                 :token/created-at :token/revoked?])
                 :in $ ?h
                 :where [?t :token/hash ?h]]
               db hash)))

(defn validate-token
  "Validate a raw token string. Returns {:id uuid :role kw} or nil.
   Returns nil for revoked tokens."
  [meta-db raw-token]
  (when-let [ent (token-entity meta-db (hash-token raw-token))]
    (when-not (:token/revoked? ent)
      {:id   (:token/id ent)
       :role (:token/role ent)})))

(defn list-tokens
  "List all tokens (never includes the raw value or hash).
   Returns [{:id uuid :role kw :label str :created-at inst :revoked? bool}]."
  [meta-db]
  (->> (d/q '[:find (pull ?t [:token/id :token/role :token/label
                              :token/created-at :token/revoked?])
              :where [?t :token/id]]
            meta-db)
       (map first)
       (mapv (fn [t]
               {:id         (:token/id t)
                :role       (:token/role t)
                :label      (:token/label t)
                :created-at (:token/created-at t)
                :revoked?   (boolean (:token/revoked? t))}))
       (sort-by :created-at)))

(defn revoke-token!
  "Revoke a token by UUID. Returns true if found, false if not."
  [meta-conn id]
  (let [db  (d/db meta-conn)
        eid (ffirst (d/q '[:find ?t :in $ ?id :where [?t :token/id ?id]] db id))]
    (if eid
      (do (d/transact meta-conn {:tx-data [[:db/add eid :token/revoked? true]]})
          true)
      false)))

;; --- Role checks ---

(def ^:private admin-only-prefixes
  "URI path prefixes that require admin role."
  #{"/api/import" "/api/analyze" "/api/enrich" "/api/update"
    "/api/synthesize" "/api/digest" "/api/introspect" "/api/reseed"
    "/api/tokens" "/api/repos" "/api/webhook" "/api/delta"
    "/api/settings" "/api/query-raw" "/api/ask/sessions"})

(def ^:private write-prefixes
  "URI path prefixes that perform writes (used for read-only mode checks).
   Superset of admin-only-prefixes plus endpoints that write to meta-db."
  (into admin-only-prefixes #{"/api/ask"}))

(def ^:private admin-only-methods
  "Method + path combos that need admin even though GET is normally reader-safe."
  #{[:delete "/api/databases"]})

(defn requires-admin?
  "True if this request requires admin role."
  [method path]
  (or (some #(clojure.string/starts-with? path %) admin-only-prefixes)
      (and (= method :delete)
           (some #(clojure.string/starts-with? path (second %))
                 (filter #(= (first %) :delete) admin-only-methods)))))

(defn writes-data?
  "True if this request performs writes (superset of requires-admin?)."
  [method path]
  (or (requires-admin? method path)
      (and (= method :post)
           (some #(clojure.string/starts-with? path %) write-prefixes))))
