(ns noumenon.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.client.api :as d]))

;; --- Loading ---

(defn load-edn-resource
  "Load and parse an EDN file from the classpath. Returns nil if not found."
  [resource-path]
  (when-let [url (io/resource resource-path)]
    (-> url slurp edn/read-string)))

(defn load-rules
  "Load reusable Datalog rules from resources/queries/rules.edn."
  []
  (load-edn-resource "queries/rules.edn"))

(def ^:private query-names
  ["files-by-complexity"
   "files-by-layer"
   "component-dependencies"
   "co-changed-files"
   "top-contributors"])

(defn load-named-query
  "Load a named query definition. Returns the query map or nil if not found."
  [query-name]
  (load-edn-resource (str "queries/" query-name ".edn")))

(defn list-query-names
  "Return the list of available named query names."
  []
  query-names)

;; --- Execution ---

(defn execute-query
  "Execute a named query against the given database.
   Returns the raw result set (set of tuples)."
  [db query-def]
  (let [{:keys [query uses-rules]} query-def
        rules (when uses-rules (load-rules))]
    (if uses-rules
      (d/q query db rules)
      (d/q query db))))

(defn run-named-query
  "Load and execute a named query. Returns {:ok results} or {:error message}."
  [db query-name]
  (if-let [query-def (load-named-query query-name)]
    {:ok (execute-query db query-def)}
    {:error (str "Unknown query: " query-name
                 ". Available: " (pr-str (list-query-names)))}))
