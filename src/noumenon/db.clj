(ns noumenon.db
  (:require [clojure.java.io :as io]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.schema :as schema]))

(defn create-client
  "Create a Datomic Local client.
   - In-memory: (create-client :mem)
   - Directory-backed: (create-client \"/path/to/storage\")"
  [storage-dir]
  (d/client {:server-type :datomic-local
             :storage-dir storage-dir
             :system      "noumenon"}))

(defn- not-found-anomaly?
  "True if `e` is a Datomic ex-info carrying a `:not-found` anomaly —
   what Datomic throws when the system catalog references a db whose
   on-disk directory is missing."
  [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= :cognitect.anomalies/not-found
          (:cognitect.anomalies/category (ex-data e)))))

(defn create-db
  "Create a database and return a connection. Creates the DB if it doesn't
   exist.

   Recovers from a stale-catalog state: when Datomic-Local's system
   catalog still references a db whose on-disk directory has been
   removed externally (e.g. `bb prune-deltas` deleted a delta dir, or
   the user wiped one manually), `create-database` is a no-op against
   the catalog and `connect` throws `:cognitect.anomalies/not-found`.
   We catch that exact anomaly, drop the stale catalog entry, and
   recreate cleanly. Done in one place rather than at every caller —
   `connect` is wrapped here so cache misses, fresh connects, and
   schema-ensure paths all share the same recovery."
  [client db-name]
  (d/create-database client {:db-name db-name})
  (try
    (d/connect client {:db-name db-name})
    (catch clojure.lang.ExceptionInfo e
      (if (not-found-anomaly? e)
        (do
          (d/delete-database client {:db-name db-name})
          (d/create-database client {:db-name db-name})
          (d/connect client {:db-name db-name}))
        (throw e)))))

(defn connect-and-ensure-schema
  "Create a database, connect, and transact all schema. Returns the connection.
   - storage-dir: :mem for in-memory, or a path string for directory-backed
   - db-name: database name"
  [storage-dir db-name]
  (let [client (create-client storage-dir)
        conn   (create-db client db-name)]
    (schema/ensure-schema conn)
    conn))

;; --- Connection cache (shared across mcp, http, etc.) ---

(defonce conn-cache (atom {}))

(defn get-or-create-conn
  "Get or create a connection, keyed on [db-dir db-name].
   Thread-safe via locking."
  [db-dir db-name]
  (let [cache-key [db-dir db-name]]
    (or (get @conn-cache cache-key)
        (locking conn-cache
          (or (get @conn-cache cache-key)
              (let [conn (connect-and-ensure-schema db-dir db-name)]
                (swap! conn-cache assoc cache-key conn)
                conn))))))

(defn evict-conn!
  "Remove a cached connection for [db-dir db-name]."
  [db-dir db-name]
  (swap! conn-cache dissoc [db-dir db-name]))

(defn release-conns!
  "Release all cached Datomic connections. Datomic-Local doesn't expose a
   close method on the connection itself, so this just drops the cache so
   the next request reopens a fresh client+conn pair. Called on daemon
   shutdown so a restart doesn't observe a stale conn map."
  []
  (reset! conn-cache {}))

(def meta-db-name
  "Reserved db-name for the daemon's meta database (tokens, settings,
   prompts, rules, ask sessions, benchmark/introspect history). Callers
   that gate on 'this is the meta DB, not a user repo' must compare
   against this constant rather than the bare string."
  "noumenon-internal")

(defn ensure-meta-db
  "Connect to the meta database, ensure schema, and seed artifacts from
   classpath if needed. Returns the connection. Uses conn-cache so
   repeated calls reuse the same connection."
  [storage-dir]
  (let [conn (get-or-create-conn storage-dir meta-db-name)]
    (artifacts/seed-from-classpath! conn)
    conn))

(defn delete-db
  "Delete a database by name. Returns true if it existed."
  [client db-name]
  (d/delete-database client {:db-name db-name}))

(defn list-db-dirs
  "Return sorted seq of database names found in the storage dir."
  [db-dir]
  (some->> (io/file db-dir "noumenon") .listFiles
           (filter #(.isDirectory %))
           (sort-by #(.getName %))
           (mapv #(.getName %))))

(defn- tx-op-counts
  "Return map of {:import n :analyze n :enrich n} from tx metadata.
   Enrich count is the number of dependency edges, not transactions."
  [db]
  (let [ops (->> (d/q '[:find ?op (count ?tx) :where [?tx :tx/op ?op]] db)
                 (into {}))]
    (cond-> ops
      (contains? ops "enrich")
      (assoc "enrich" (or (ffirst (d/q '[:find (count ?e)
                                         :where [?e :file/imports _]]
                                       db))
                          0)))))

(defn db-stats
  "Get stats for a database. Pass a pre-created client to avoid creating
   a new one per call when iterating over multiple databases."
  [client db-name]
  (try
    (let [conn   (d/connect client {:db-name db-name})
          db     (d/db conn)
          latest (ffirst (d/q '[:find (max ?d) :where [_ :commit/committed-at ?d]] db))
          cost   (or (ffirst (d/q '[:find (sum ?c) :where [_ :tx/cost-usd ?c]] db)) 0.0)
          ops    (tx-op-counts db)]
      {:name    db-name
       :commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
       :files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
       :dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))
       :latest  latest
       :cost    cost
       :ops     ops})
    (catch Exception e
      {:name db-name :error (.getMessage e)})))
