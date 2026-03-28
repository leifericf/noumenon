(ns noumenon.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]))

(defn load-edn-resource
  "Load and parse an EDN file from the classpath. Returns nil if not found.
   Used for non-artifact resources (model config, benchmark questions, etc.)."
  [resource-path]
  (when-let [url (io/resource resource-path)]
    (-> url slurp edn/read-string)))

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
  "Return {:commits n :files n :dirs n :head-sha str} entity counts and
   the stored HEAD SHA (the last commit imported/synced) for the database."
  [db]
  {:commits  (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
   :files    (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
   :dirs     (count (d/q '[:find ?e :where [?e :dir/path _]] db))
   :head-sha (ffirst (d/q '[:find ?sha :where [_ :repo/head-sha ?sha]] db))})

;; --- Execution ---

(defn execute-query
  "Execute a query-def against the given database.
   `rules` is the parsed rules vector (required when query-def has :uses-rules).
   `params` is an optional map of input values for parameterized queries."
  ([db query-def rules] (execute-query db query-def rules nil))
  ([db query-def rules params]
   (let [{:keys [query uses-rules inputs]} query-def
         extra-args (when (seq inputs)
                      (mapv params inputs))
         args      (cond-> [query db]
                     uses-rules (conj rules)
                     (seq extra-args) (into extra-args))]
     (apply d/q args))))

(defn run-named-query
  "Load and execute a named query. Returns {:ok results} or {:error message}.
   Validates query-name against the active allowlist in the meta database.
   `params` is an optional map for parameterized queries."
  ([meta-db db query-name] (run-named-query meta-db db query-name {}))
  ([meta-db db query-name params]
   (let [active (set (artifacts/list-active-query-names meta-db))]
     (if-not (active query-name)
       {:error (str "Unknown query: " query-name
                    ". Available: " (pr-str (sort active)))}
       (if-let [query-def (artifacts/load-named-query meta-db query-name)]
         (if-let [missing (seq (remove (set (keys params)) (:inputs query-def)))]
           {:error (str "Missing required inputs: " (pr-str (vec missing)))}
           {:ok (execute-query db query-def (artifacts/load-rules meta-db) params)})
         {:error (str "Query '" query-name "' is active but has no definition.")})))))
