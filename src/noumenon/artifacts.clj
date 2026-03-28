(ns noumenon.artifacts
  "Read and write prompt templates, named queries, and rules in Datomic.
   Classpath EDN files are seed-only — loaded on first run, never read at runtime."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.client.api :as d]))

;; --- Constants ---

(def ^:private chunk-size
  "Maximum characters per chunk. Datomic Local limits string values to ~4KB."
  4000)

;; --- Classpath seed helpers ---

(defn- load-edn-resource
  "Load and parse an EDN file from the classpath. Returns nil if not found."
  [resource-path]
  (some-> (io/resource resource-path) slurp edn/read-string))

(defn- query-seed-tx
  "Build a transaction map for a single named query from its classpath EDN."
  [query-name active-set]
  (when-let [qdef (load-edn-resource (str "queries/" query-name ".edn"))]
    (cond-> {:artifact.query/name        query-name
             :artifact.query/description (:description qdef "")
             :artifact.query/query-edn   (pr-str (:query qdef))
             :artifact.query/active      (contains? active-set query-name)}
      (:uses-rules qdef)  (assoc :artifact.query/uses-rules true)
      (seq (:inputs qdef)) (assoc :artifact.query/inputs (pr-str (:inputs qdef))))))

(defn- chunk-template
  "Split a template string into chunk transaction maps if it exceeds chunk-size.
   Returns either {:artifact.prompt/template s} for small templates,
   or {:artifact.prompt/chunks [...]} for large ones."
  [template]
  (if (<= (count template) chunk-size)
    {:artifact.prompt/template template}
    (let [chunks (->> (partition-all chunk-size template)
                      (map-indexed (fn [i chars]
                                     {:artifact.chunk/ordinal i
                                      :artifact.chunk/content (apply str chars)}))
                      vec)]
      {:artifact.prompt/chunks chunks})))

(defn- prompt-seed-tx
  "Build a transaction map for a single prompt from its classpath EDN."
  [prompt-name]
  (when-let [raw (load-edn-resource (str "prompts/" prompt-name ".edn"))]
    (let [template (cond
                     (string? (:template raw)) (:template raw)
                     :else                     (pr-str raw))]
      (cond-> (merge {:artifact.prompt/name prompt-name}
                     (chunk-template template))
        (:version raw) (assoc :artifact.prompt/version (:version raw))))))

;; --- Seeding ---

(defn seeded?
  "True if the meta database already contains artifact entities."
  [db]
  (boolean (seq (d/q '[:find ?e :where [?e :artifact.query/name _]] db))))

(defn- do-seed!
  "Upsert all classpath EDN resources into the meta database."
  [conn source]
  (let [index     (load-edn-resource "queries/index.edn")
        active    (set index)
        query-txs (->> index
                       (keep #(query-seed-tx % active))
                       vec)
        tx-meta   {:db/id "datomic.tx"
                   :tx/op :seed
                   :tx/artifact-source source}]
    ;; Seed queries
    (when (seq query-txs)
      (d/transact conn {:tx-data (conj query-txs tx-meta)}))
    ;; Seed rules
    (when-let [rules-str (some-> (io/resource "queries/rules.edn") slurp)]
      (d/transact conn {:tx-data [{:artifact.rules/id  "default"
                                   :artifact.rules/edn rules-str}
                                  tx-meta]}))
    ;; Seed prompts
    (doseq [pname ["agent-system" "agent-examples" "analyze-file" "introspect"]]
      (when-let [tx (prompt-seed-tx pname)]
        (d/transact conn {:tx-data [tx tx-meta]})))))

(defn seed-from-classpath!
  "Load all classpath EDN resources into Datomic. Idempotent — skips if already seeded."
  [conn]
  (when-not (seeded? (d/db conn))
    (do-seed! conn :bootstrap)))

(defn reseed!
  "Unconditionally upsert all classpath resources into Datomic.
   Identity attributes make this an upsert — changed values update,
   unchanged are no-ops. Datomic history preserves prior values."
  [conn]
  (do-seed! conn :reseed))

;; --- Reading ---

(defn load-named-query
  "Load a named query definition from Datomic.
   Returns {:name str :description str :query vec :inputs vec :uses-rules bool}
   or nil if not found."
  [meta-db query-name]
  (let [entity (d/pull meta-db '[*] [:artifact.query/name query-name])]
    (when (:artifact.query/query-edn entity)
      {:name        query-name
       :description (:artifact.query/description entity)
       :query       (edn/read-string (:artifact.query/query-edn entity))
       :uses-rules  (:artifact.query/uses-rules entity false)
       :inputs      (some-> (:artifact.query/inputs entity) edn/read-string)})))

(defn list-active-query-names
  "Return sorted vector of active query names from Datomic."
  [meta-db]
  (->> (d/q '[:find ?name
              :where
              [?e :artifact.query/name ?name]
              [?e :artifact.query/active true]]
            meta-db)
       (map first)
       sort
       vec))

(defn load-rules
  "Load Datalog rules from Datomic. Returns the parsed rules vector or nil."
  [meta-db]
  (some-> (d/pull meta-db '[:artifact.rules/edn] [:artifact.rules/id "default"])
          :artifact.rules/edn
          edn/read-string))

(defn load-prompt
  "Load a prompt template by name from Datomic. Returns the template string or nil.
   Transparently reassembles chunked templates."
  [meta-db prompt-name]
  (let [entity (d/pull meta-db '[:artifact.prompt/template
                                 {:artifact.prompt/chunks
                                  [:artifact.chunk/ordinal
                                   :artifact.chunk/content]}]
                       [:artifact.prompt/name prompt-name])]
    (or (:artifact.prompt/template entity)
        (some->> (:artifact.prompt/chunks entity)
                 (sort-by :artifact.chunk/ordinal)
                 (map :artifact.chunk/content)
                 (apply str)
                 not-empty))))

(defn load-prompt-version
  "Load a prompt's version string from Datomic. Returns nil if unset."
  [meta-db prompt-name]
  (:artifact.prompt/version
   (d/pull meta-db '[:artifact.prompt/version] [:artifact.prompt/name prompt-name])))

;; --- Writing ---

(defn save-prompt!
  "Write a prompt template to Datomic. Handles chunking transparently."
  [conn prompt-name template source]
  (let [tx-data (merge {:artifact.prompt/name prompt-name}
                       (chunk-template template))]
    (d/transact conn {:tx-data [tx-data
                                {:db/id "datomic.tx"
                                 :tx/artifact-source source}]})))

(defn save-rules!
  "Write rules to Datomic."
  [conn rules-edn-str source]
  (d/transact conn {:tx-data [{:artifact.rules/id  "default"
                               :artifact.rules/edn rules-edn-str}
                              {:db/id "datomic.tx"
                               :tx/artifact-source source}]}))

;; --- Mutation ---

(defn activate-query!
  "Mark a query as active (available for execution)."
  [conn query-name source]
  (d/transact conn {:tx-data [{:artifact.query/name   query-name
                               :artifact.query/active true}
                              {:db/id "datomic.tx"
                               :tx/artifact-source source}]}))

(defn deactivate-query!
  "Mark a query as inactive (hidden from allowlist)."
  [conn query-name source]
  (d/transact conn {:tx-data [{:artifact.query/name   query-name
                               :artifact.query/active false}
                              {:db/id "datomic.tx"
                               :tx/artifact-source source}]}))

;; --- History ---

(defn prompt-history
  "Return the history of a prompt template as a vector of
   {:template str :tx-time inst :source kw}, newest first.
   For chunked prompts, returns the raw chunk EDN rather than reassembling."
  [meta-conn prompt-name]
  (let [hdb (d/history (d/db meta-conn))]
    (->> (d/q '[:find ?template ?inst ?source
                :in $ ?name
                :where
                [?e :artifact.prompt/name ?name]
                [?e :artifact.prompt/template ?template ?tx true]
                [?tx :db/txInstant ?inst]
                [(get-else $ ?tx :tx/artifact-source :unknown) ?source]]
              hdb prompt-name)
         (sort-by second #(compare %2 %1))
         (mapv (fn [[template inst source]]
                 {:template template :tx-time inst :source source})))))

(defn rules-history
  "Return the history of rules changes, newest first."
  [meta-conn]
  (let [hdb (d/history (d/db meta-conn))]
    (->> (d/q '[:find ?edn ?inst ?source
                :where
                [?e :artifact.rules/id "default"]
                [?e :artifact.rules/edn ?edn ?tx true]
                [?tx :db/txInstant ?inst]
                [(get-else $ ?tx :tx/artifact-source :unknown) ?source]]
              hdb)
         (sort-by second #(compare %2 %1))
         (mapv (fn [[edn-str inst source]]
                 {:edn edn-str :tx-time inst :source source})))))

(defn query-at
  "Load a named query as it existed at a specific point in time."
  [meta-conn query-name ^java.util.Date as-of]
  (load-named-query (d/as-of (d/db meta-conn) as-of) query-name))
