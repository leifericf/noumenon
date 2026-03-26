(ns noumenon.db
  (:require [datomic.client.api :as d]
            [noumenon.schema :as schema]))

(defn create-client
  "Create a Datomic Local client.
   - In-memory: (create-client :mem)
   - Directory-backed: (create-client \"/path/to/storage\")"
  [storage-dir]
  (d/client {:server-type :datomic-local
             :storage-dir storage-dir
             :system      "noumenon"}))

(defn create-db
  "Create a database and return a connection. Creates the DB if it doesn't exist."
  [client db-name]
  (d/create-database client {:db-name db-name})
  (d/connect client {:db-name db-name}))

(defn connect-and-ensure-schema
  "Create a database, connect, and transact all schema. Returns the connection.
   - storage-dir: :mem for in-memory, or a path string for directory-backed
   - db-name: database name"
  [storage-dir db-name]
  (let [client (create-client storage-dir)
        conn   (create-db client db-name)]
    (schema/ensure-schema conn)
    conn))
