(ns noumenon.query
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set]
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

;; --- Federation: per-path exclusion injection ---

(defn inject-exclusions
  "Append `(not [?file :file/path \"<p>\"])` clauses to the query's :where
   for each excluded path. Returns the augmented query.

   Convention: federation-safe queries bind the file entity as `?file`. Queries
   that use a different binding name aren't federation-safe in v1.

   Vector-form queries only — adds clauses at the end (Datalog treats every
   form after :where as a where clause)."
  [query exclude-paths]
  (if (empty? exclude-paths)
    query
    (into (vec query)
          (map (fn [p] (list 'not ['?file :file/path p])) exclude-paths))))

(defn execute-query
  "Execute a query-def against the given database.
   `rules` is the parsed rules vector (required when query-def has :uses-rules).
   `params` is an optional map of input values for parameterized queries.
   `opts` may include :exclude-paths — when the query is federation-safe and
   exclude-paths is non-empty, `(not [?file :file/path p])` clauses are appended
   to the :where."
  ([db query-def rules] (execute-query db query-def rules nil nil))
  ([db query-def rules params] (execute-query db query-def rules params nil))
  ([db query-def rules params {:keys [exclude-paths]}]
   (let [{:keys [query uses-rules inputs federation-safe?]} query-def
         q          (cond-> query
                      (and federation-safe? (seq exclude-paths))
                      (inject-exclusions exclude-paths))
         extra-args (when (seq inputs) (mapv params inputs))
         args       (cond-> [q db]
                      uses-rules (conj rules)
                      (seq extra-args) (into extra-args))]
     (apply d/q args))))

(defn run-named-query
  "Load and execute a named query. Returns
     {:ok results :federation-safe? bool}
   or {:error message :federation-safe? bool}.

   `:federation-safe?` reflects the query's metadata — independent of whether
   :exclude-paths was supplied, so the caller (launcher) knows whether to merge
   delta-DB rows on top.

   Validates query-name against the active allowlist in the meta database.
   `params` is an optional map for parameterized queries.
   `opts` may include :exclude-paths for federation-safe queries."
  ([meta-db db query-name] (run-named-query meta-db db query-name {} {}))
  ([meta-db db query-name params] (run-named-query meta-db db query-name params {}))
  ([meta-db db query-name params opts]
   (let [active (set (artifacts/list-active-query-names meta-db))]
     (if-not (active query-name)
       {:error (str "Unknown query: " query-name
                    ". Available: " (pr-str (sort active)))
        :federation-safe? false}
       (if-let [query-def (artifacts/load-named-query meta-db query-name)]
         (if-let [missing (seq (remove (set (keys params)) (:inputs query-def)))]
           {:error (str "Missing required inputs: " (pr-str (vec missing)))
            :federation-safe? (boolean (:federation-safe? query-def))}
           {:ok (execute-query db query-def (artifacts/load-rules meta-db) params opts)
            :federation-safe? (boolean (:federation-safe? query-def))})
         {:error (str "Query '" query-name "' is active but has no definition.")
          :federation-safe? false})))))

;; --- Federation merge ---

(defn delta-tombstoned-paths
  "Return paths that are tombstoned (`:file/deleted? true`) in the delta —
   files the developer's branch has removed. Trunk should exclude these
   from federated results so they don't show in branch-view answers."
  [delta-db]
  (mapv first (d/q '[:find ?p
                     :where
                     [?f :file/deleted? true]
                     [?f :file/path ?p]]
                   delta-db)))

(defn delta-added-paths
  "Return paths the delta has but the trunk basis didn't — i.e., new files
   added in the developer's branch. Used to scope which delta rows we
   merge on top of trunk; files modified in the delta keep trunk's
   authoritative history view."
  [trunk-db delta-db]
  (let [delta-paths (set (mapv first (d/q '[:find ?p
                                            :where
                                            [?f :file/path ?p]
                                            (not [?f :file/deleted? true])]
                                          delta-db)))
        trunk-paths (set (mapv first (d/q '[:find ?p
                                            :where [_ :file/path ?p]]
                                          trunk-db)))]
    (vec (clojure.set/difference delta-paths trunk-paths))))

(defn run-federated-query
  "Run a named query across a trunk DB and a delta DB and merge results.

   v1 merge semantics — `:tombstone-only`:
     • Trunk rows: full trunk view, MINUS files the delta has tombstoned.
       Modified-in-delta files keep trunk's history (matters for hotspots,
       files-by-churn, complex-hotspots, bug-hotspots — anything that
       aggregates over commits or other data the sparse delta lacks).
     • Delta rows: only files ADDED in the branch (not in trunk). Modified
       files don't double-count with trunk; deleted files (tombstones) are
       skipped — they were already excluded above.

   The earlier 'exclude all delta paths from trunk + append delta rows'
   merge made modified files disappear from churn-based queries because
   the delta DB has no commits to carry their history. Tombstone-only
   keeps history intact while still respecting branch deletions.

   Non-federation-safe queries return trunk-only with `:federation-safe?
   false` so the caller can show a banner."
  [meta-db trunk-db delta-db query-name params]
  (let [excludes     (delta-tombstoned-paths delta-db)
        trunk-result (run-named-query meta-db trunk-db query-name params
                                      {:exclude-paths excludes})]
    (if (:error trunk-result)
      trunk-result
      (let [fsafe?       (:federation-safe? trunk-result)
            trunk-rows   (vec (:ok trunk-result))
            added-paths  (when fsafe? (delta-added-paths trunk-db delta-db))
            delta-result (when (and fsafe? (seq added-paths))
                           (run-named-query meta-db delta-db query-name params))
            delta-rows   (when (seq added-paths)
                           (filterv #(some #{(first %)} added-paths)
                                    (vec (:ok delta-result []))))
            rows         (if fsafe? (into trunk-rows (or delta-rows [])) trunk-rows)]
        {:ok               rows
         :trunk-count      (count trunk-rows)
         :delta-count      (count (or delta-rows []))
         :federation-safe? fsafe?}))))
