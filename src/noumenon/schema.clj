(ns noumenon.schema
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.client.api :as d]))

(def schema-files
  ["schema/core.edn"
   "schema/architecture.edn"
   "schema/provenance.edn"
   "schema/benchmark.edn"
   "schema/introspect.edn"
   "schema/artifacts.edn"
   "schema/settings.edn"
   "schema/ask.edn"
   "schema/synthesis.edn"
   "schema/auth.edn"])

(defn load-schema
  "Read a schema EDN file from the classpath. Throws if file not found."
  [resource-path]
  (if-let [url (io/resource resource-path)]
    (-> url slurp edn/read-string)
    (throw (ex-info (str "Schema file not found on classpath: " resource-path)
                    {:file resource-path}))))

(defn transact-schema
  "Transact a single schema file into the given connection."
  [conn resource-path]
  (->> resource-path load-schema (hash-map :tx-data) (d/transact conn)))

(defn ensure-schema
  "Load and transact all schema files. Idempotent — safe to call repeatedly."
  [conn]
  (run! (partial transact-schema conn) schema-files))
