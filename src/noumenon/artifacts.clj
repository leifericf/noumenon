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

(def ^:private valid-federation-modes
  #{:tombstone-only :added-files-merge})

(defn- query-seed-tx
  "Build a transaction map for a single named query from its classpath EDN.
   :federation-mode (when present) populates the new attribute and also
   sets the legacy :federation-safe? boolean for response-shape backward
   compatibility (the launcher's banner reads it)."
  [query-name active-set]
  (when-let [qdef (load-edn-resource (str "queries/" query-name ".edn"))]
    (let [mode (:federation-mode qdef)]
      (when (and mode (not (valid-federation-modes mode)))
        (throw (ex-info (str "Query '" query-name "' has invalid :federation-mode "
                             (pr-str mode) ". Must be one of " valid-federation-modes
                             " or omitted.")
                        {:query query-name :mode mode})))
      (cond-> {:artifact.query/name        query-name
               :artifact.query/description (:description qdef "")
               :artifact.query/query-edn   (pr-str (:query qdef))
               :artifact.query/active      (contains? active-set query-name)}
        (:uses-rules qdef)   (assoc :artifact.query/uses-rules true)
        (seq (:inputs qdef)) (assoc :artifact.query/inputs (pr-str (:inputs qdef)))
        mode                 (assoc :artifact.query/federation-mode mode
                                    :artifact.query/federation-safe? true)))))

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

;; --- Seeding (defined after save-prompt! — see below) ---
;; Forward declarations for seed functions used by db/ensure-meta-db
(declare seed-from-classpath! reseed!)

;; --- Reading ---

(defn load-named-query
  "Load a named query definition from Datomic.
   Returns {:name str :description str :query vec :inputs vec :uses-rules bool
            :federation-mode kw :federation-safe? bool} or nil if not found.
   :federation-safe? is derived from :federation-mode for the response-shape
   the launcher already understands; absent mode = not federation-safe."
  [meta-db query-name]
  (let [entity (d/pull meta-db '[*] [:artifact.query/name query-name])]
    (when (:artifact.query/query-edn entity)
      (let [mode (:artifact.query/federation-mode entity)]
        {:name             query-name
         :description      (:artifact.query/description entity)
         :query            (edn/read-string {:readers {}} (:artifact.query/query-edn entity))
         :uses-rules       (:artifact.query/uses-rules entity false)
         :inputs           (some-> (:artifact.query/inputs entity) (->> (edn/read-string {:readers {}})))
         :federation-mode  mode
         :federation-safe? (some? mode)}))))

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
          (->> (edn/read-string {:readers {}}))))

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
  "Write a prompt template to Datomic. Handles chunking transparently.
   Retracts the opposite storage format to prevent stale data."
  [conn prompt-name template source]
  (let [db       (d/db conn)
        entity   (d/pull db '[:db/id :artifact.prompt/template
                              {:artifact.prompt/chunks [:db/id]}]
                         [:artifact.prompt/name prompt-name])
        eid      (:db/id entity)
        chunked? (> (count template) chunk-size)
        ;; Retract stale data: old template string and/or old chunks
        retracts (when eid
                   (into (when (and chunked? (:artifact.prompt/template entity))
                           [[:db/retract eid :artifact.prompt/template
                             (:artifact.prompt/template entity)]])
                         (map (fn [chunk] [:db/retractEntity (:db/id chunk)]))
                         (:artifact.prompt/chunks entity)))
        tx-data  (merge {:artifact.prompt/name prompt-name}
                        (chunk-template template))]
    (d/transact conn {:tx-data (into [tx-data {:db/id "datomic.tx"
                                               :tx/artifact-source source}]
                                     retracts)})))

(defn save-rules!
  "Write rules to Datomic."
  [conn rules-edn-str source]
  (d/transact conn {:tx-data [{:artifact.rules/id  "default"
                               :artifact.rules/edn rules-edn-str}
                              {:db/id "datomic.tx"
                               :tx/artifact-source source}]}))

;; --- Seeding (after save-prompt! so we can retract stale chunks) ---

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
    ;; Seed prompts — use save-prompt! to retract stale chunks
    (doseq [pname ["agent-system" "agent-examples" "analyze-file" "introspect"
                   "synthesize" "synthesize-partition" "synthesize-merge"]]
      (when-let [raw (load-edn-resource (str "prompts/" pname ".edn"))]
        (let [template (cond
                         (string? (:template raw)) (:template raw)
                         :else                     (pr-str raw))]
          (save-prompt! conn pname template source))))))

(defn seed-from-classpath!
  "Upsert all classpath EDN resources into Datomic on every startup.
   Identity attributes make unchanged entities no-ops, so this is fast and safe."
  [conn]
  (do-seed! conn :bootstrap))

(defn reseed!
  "Unconditionally upsert all classpath resources into Datomic.
   Identity attributes make this an upsert — changed values update,
   unchanged are no-ops. Datomic history preserves prior values."
  [conn]
  (do-seed! conn :reseed))

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

(defn- template-history-rows
  "History rows for prompts stored as a single template string."
  [hdb prompt-name]
  (d/q '[:find ?template ?inst ?source
         :in $ ?name
         :where
         [?e :artifact.prompt/name ?name]
         [?e :artifact.prompt/template ?template ?tx true]
         [?tx :db/txInstant ?inst]
         [(get-else $ ?tx :tx/artifact-source :unknown) ?source]]
       hdb prompt-name))

(defn- chunk-history-txs
  "Distinct transaction instants where chunks were asserted for a prompt."
  [hdb prompt-name]
  (d/q '[:find ?tx ?inst ?source
         :in $ ?name
         :where
         [?e :artifact.prompt/name ?name]
         [?e :artifact.prompt/chunks ?chunk ?tx true]
         [?chunk :artifact.chunk/content _ ?tx true]
         [?tx :db/txInstant ?inst]
         [(get-else $ ?tx :tx/artifact-source :unknown) ?source]]
       hdb prompt-name))

(defn- reassemble-chunks-as-of
  "Reassemble chunked template content as of a specific transaction time."
  [meta-conn prompt-name ^java.util.Date as-of]
  (let [db (d/as-of (d/db meta-conn) as-of)]
    (load-prompt db prompt-name)))

(defn prompt-history
  "Return the history of a prompt template as a vector of
   {:template str :tx-time inst :source kw}, newest first.
   For chunked prompts, reassembles chunks as they existed at each transaction."
  [meta-conn prompt-name]
  (let [hdb       (d/history (d/db meta-conn))
        templates (->> (template-history-rows hdb prompt-name)
                       (mapv (fn [[template inst source]]
                               {:template template :tx-time inst :source source})))
        chunks    (->> (chunk-history-txs hdb prompt-name)
                       (mapv (fn [[_tx inst source]]
                               {:template (reassemble-chunks-as-of meta-conn prompt-name inst)
                                :tx-time  inst
                                :source   source})))]
    (->> (into templates chunks)
         (sort-by :tx-time #(compare %2 %1))
         vec)))

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

;; --- Settings ---

(defn get-all-settings
  "Return all settings as a map of {key-string value-edn}."
  [meta-db]
  (->> (d/q '[:find ?k ?v :where [?e :setting/key ?k] [?e :setting/value ?v]] meta-db)
       (into {} (map (fn [[k v]] [k (edn/read-string {:readers {}} v)])))))

(defn get-setting
  "Get a single setting value by key. Returns nil if not found."
  [meta-db key]
  (some-> (d/q '[:find ?v :in $ ?k :where [?e :setting/key ?k] [?e :setting/value ?v]]
               meta-db key)
          ffirst
          (edn/read-string {:readers {}})))

(defn set-setting!
  "Upsert a setting. Value is stored as pr-str EDN."
  [meta-conn key value]
  (d/transact meta-conn
              {:tx-data [{:setting/key key :setting/value (pr-str value)}]}))
