(ns noumenon.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn load-named-query
  "Load a named query definition. Returns the query map or nil if not found."
  [query-name]
  (load-edn-resource (str "queries/" query-name ".edn")))

(defn list-query-names
  "Return available named queries from resources/queries/index.edn."
  []
  (->> (load-edn-resource "queries/index.edn")
       (sort)
       vec))

;; --- Schema introspection ---

(defn list-attributes
  "Return a sorted seq of {:ident kw :value-type kw :cardinality kw :doc str}
   for all user-defined attributes (excludes :db and :fressian prefixes)."
  [db]
  (->> (d/q '[:find ?ident ?vt ?card ?doc
              :where
              [?a :db/ident ?ident]
              [?a :db/valueType ?vt]
              [?a :db/cardinality ?card]
              [(get-else $ ?a :db/doc "") ?doc]]
            db)
       (remove (fn [[ident]]
                 (or (#{"db" "db.type" "db.cardinality" "db.unique" "fressian"}
                      (namespace ident))
                     (str/starts-with? (name ident) "db"))))
       (sort-by first)
       (mapv (fn [[ident vt card doc]]
               {:ident       ident
                :value-type  vt
                :cardinality card
                :doc         doc}))))

(defn schema-summary
  "Return a human-readable string summarizing all user-defined attributes."
  [db]
  (->> (list-attributes db)
       (map (fn [{:keys [ident value-type cardinality doc]}]
              (str ident " " value-type " " cardinality
                   (when (seq doc) (str " — " doc)))))
       (str/join "\n")))

;; --- Common queries ---

(defn repo-stats
  "Return {:commits n :files n :dirs n} entity counts for the database."
  [db]
  {:commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
   :files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
   :dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))})

;; --- Execution ---

(defn execute-query
  "Execute a named query against the given database.
   `params` is an optional map of input values for parameterized queries,
   keyed by the names declared in the query-def's :inputs vector."
  ([db query-def] (execute-query db query-def nil))
  ([db query-def params]
   (let [{:keys [query uses-rules inputs]} query-def
         rules     (when uses-rules (load-rules))
         extra-args (when (seq inputs)
                      (mapv #(get params %) inputs))
         args      (cond-> [query db]
                     uses-rules (conj rules)
                     (seq extra-args) (into extra-args))]
     (apply d/q args))))

(defn run-named-query
  "Load and execute a named query. Returns {:ok results} or {:error message}.
   Validates query-name against the index allowlist before loading.
   `params` is an optional map for parameterized queries."
  ([db query-name] (run-named-query db query-name {}))
  ([db query-name params]
   (if-not ((set (list-query-names)) query-name)
     {:error (str "Unknown query: " query-name
                  ". Available: " (pr-str (list-query-names)))}
     (if-let [query-def (load-named-query query-name)]
       (if-let [missing (seq (remove (set (keys params)) (:inputs query-def)))]
         {:error (str "Missing required inputs: " (pr-str (vec missing)))}
         {:ok (execute-query db query-def params)})
       {:error (str "Query '" query-name "' is in the index but its definition file is missing.")}))))
