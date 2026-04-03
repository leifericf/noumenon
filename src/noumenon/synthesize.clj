(ns noumenon.synthesize
  "Codebase-level architectural synthesis. Queries the knowledge graph
   for file summaries, import edges, and directory structure, then uses
   an LLM to identify components and classify files architecturally."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.llm :as llm]
            [noumenon.util :as util :refer [escape-double-mustache log! sha256-hex]])
  (:import [java.util Date]))

;; --- Validation sets (shared with analyze) ---

(def ^:private valid-layer
  #{:core :subsystem :driver :api :util})

(def ^:private valid-category
  #{:backend :frontend :infrastructure :configuration :data
    :testing :documentation :tooling :shared})

(def ^:private valid-complexity
  #{:trivial :simple :moderate :complex :very-complex})

(def ^:private valid-patterns
  #{:state-machine :producer-consumer :builder :factory :decorator
    :adapter :pipeline :ring-handler :middleware-chain :callback})

;; --- Prefetch from knowledge graph ---

(defn prefetch-codebase-data
  "Query the knowledge graph for all data needed by the synthesize prompt."
  [db]
  (let [files (->> (d/q '[:find ?path ?summary ?complexity ?hints
                          :where
                          [?e :file/path ?path]
                          [?e :sem/summary ?summary]
                          [(get-else $ ?e :sem/complexity :unknown) ?complexity]
                          [(get-else $ ?e :sem/synthesis-hints "") ?hints]]
                        db)
                   (mapv (fn [[path summary complexity hints]]
                           {:path path :summary summary
                            :complexity complexity :hints hints})))
        tags  (->> (d/q '[:find ?tag (count ?e)
                          :where
                          [?e :sem/tags ?tag]
                          [?e :file/path _]]
                        db)
                   (sort-by second >))
        edges (->> (d/q '[:find ?from ?to
                          :where
                          [?e :file/path ?from]
                          [?e :file/imports ?t]
                          [?t :file/path ?to]]
                        db)
                   vec)
        dirs  (->> (d/q '[:find ?dir-path (count ?e)
                          :where
                          [?e :file/path _]
                          [?e :file/directory ?d]
                          [?d :dir/path ?dir-path]]
                        db)
                   (sort-by first))]
    {:files files :tags tags :edges edges :dirs dirs}))

;; --- Formatting for prompt ---

(defn- format-dir-tree [dirs]
  (->> dirs
       (map (fn [[path cnt]] (str "  " path "/ (" cnt " files)")))
       (str/join "\n")))

(defn- format-file-summaries [files]
  (let [by-dir (group-by (fn [{:keys [path]}]
                           (let [parts (str/split path #"/")]
                             (if (> (count parts) 1)
                               (str/join "/" (butlast parts))
                               "(root)")))
                         files)]
    (->> (sort-by key by-dir)
         (map (fn [[dir fs]]
                (str "### " dir "/\n"
                     (->> fs
                          (sort-by :path)
                          (map (fn [{:keys [path summary complexity hints]}]
                                 (let [hint-str (when (seq hints)
                                                  (str " | hints: " hints))]
                                   (str "- **" path "** [" (name complexity) "] "
                                        (subs summary 0 (min 120 (count summary)))
                                        hint-str))))
                          (str/join "\n")))))
         (str/join "\n\n"))))

(defn- format-import-graph [edges]
  (let [sorted (sort edges)]
    (if (> (count sorted) 300)
      (str (count sorted) " import edges (truncated to 300):\n"
           (->> (take 300 sorted)
                (map (fn [[from to]] (str "  " from " -> " to)))
                (str/join "\n")))
      (->> sorted
           (map (fn [[from to]] (str "  " from " -> " to)))
           (str/join "\n")))))

(defn- format-tag-dist [tags]
  (->> tags
       (map (fn [[tag cnt]] (str "  " (name tag) ": " cnt)))
       (str/join "\n")))

(defn format-prefetch
  "Format prefetched data as sections for prompt substitution."
  [data]
  {:directory-tree    (format-dir-tree (:dirs data))
   :file-summaries   (format-file-summaries (:files data))
   :import-graph     (format-import-graph (:edges data))
   :tag-distribution (format-tag-dist (:tags data))})

;; --- Prompt rendering ---

(defn render-prompt
  "Substitute template variables in the synthesize prompt."
  [template repo-name data]
  (let [formatted (format-prefetch data)]
    (-> template
        (str/replace "{{repo-name}}" (str/replace repo-name #"[^a-zA-Z0-9._/-]" "_"))
        (str/replace "{{file-count}}" (str (count (:files data))))
        (str/replace "{{directory-tree}}" (escape-double-mustache (:directory-tree formatted)))
        (str/replace "{{file-summaries}}" (escape-double-mustache (:file-summaries formatted)))
        (str/replace "{{import-graph}}" (escape-double-mustache (:import-graph formatted)))
        (str/replace "{{tag-distribution}}" (escape-double-mustache (:tag-distribution formatted))))))

;; --- Response parsing ---

(defn- clamp [s] (when s (subs s 0 (min 4096 (count s)))))

(defn- validate-component [{:keys [name summary purpose layer category patterns
                                   complexity subsystem files depends-on]}]
  (when (and (string? name) (seq name) (seq files))
    (cond-> {:name name :files (vec (filter string? files))}
      (string? summary)              (assoc :summary (clamp summary))
      (string? purpose)              (assoc :purpose (clamp purpose))
      (valid-layer layer)            (assoc :layer layer)
      (valid-category category)      (assoc :category category)
      (seq (filter valid-patterns patterns)) (assoc :patterns (vec (filter valid-patterns patterns)))
      (valid-complexity complexity)  (assoc :complexity complexity)
      (string? subsystem)            (assoc :subsystem subsystem)
      (seq depends-on)               (assoc :depends-on (vec (filter string? depends-on))))))

(defn parse-response
  "Parse and validate the LLM synthesis response."
  [text]
  (when text
    (let [cleaned (analyze/strip-markdown-fences text)
          parsed  (try (edn/read-string cleaned)
                       (catch Exception e
                         (log! "synthesize/parse" (.getMessage e))
                         nil))]
      (when (map? parsed)
        {:components (->> (:components parsed) (keep validate-component) vec)}))))

;; --- Transaction data ---

(defn components->tx-data
  "Build tx-data for component entities and file->component assignments."
  [components]
  (let [known-names (set (map :name components))
        ;; Use temp IDs so refs within the same transaction work
        name->tempid (into {} (map-indexed (fn [i c] [(:name c) (str "comp-" i)]) components))
        comp-txs (->> components
                      (mapv (fn [{:keys [name summary purpose layer category
                                         patterns complexity subsystem]}]
                              (cond-> {:db/id (name->tempid name)
                                       :component/name name}
                                summary              (assoc :component/summary summary)
                                purpose              (assoc :component/purpose purpose)
                                layer                (assoc :component/layer layer)
                                category             (assoc :component/category category)
                                (seq patterns)       (assoc :component/patterns patterns)
                                complexity           (assoc :component/complexity complexity)
                                subsystem            (assoc :component/subsystem subsystem)))))
        dep-txs  (->> components
                      (keep (fn [{:keys [name depends-on]}]
                              (let [valid-deps (filter known-names depends-on)]
                                (when (seq valid-deps)
                                  {:db/id (name->tempid name)
                                   :component/depends-on (mapv #(name->tempid %) valid-deps)}))))
                      vec)
        file-txs (->> components
                      (mapcat (fn [{:keys [name files layer category]}]
                                (map (fn [path]
                                       (cond-> {:file/path path
                                                :arch/component (name->tempid name)}
                                         layer    (assoc :arch/layer layer)
                                         category (assoc :sem/category category)))
                                     files)))
                      vec)]
    (concat comp-txs dep-txs file-txs)))

;; --- Staleness detection ---

(defn needs-synthesis?
  "True if files have sem/summary but no arch/component (synthesize hasn't run)."
  [db]
  (boolean
   (seq (d/q '[:find ?e :where
               [?e :sem/summary _]
               (not [?e :arch/component _])]
             db))))

;; --- Retraction ---

(defn retraction-tx-data
  "Build tx-data to retract all component entities and synthesis-assigned file attributes.
   Does not transact — returns a vector of retraction datoms for composition."
  [db]
  (let [synth-attrs [:arch/component :arch/layer :sem/category :sem/patterns :sem/purpose]
        file-eids   (d/q '[:find ?e :where [?e :arch/component _]] db)
        comp-eids   (d/q '[:find ?e :where [?e :component/name _]] db)
        file-retractions
        (->> file-eids
             (mapcat (fn [[eid]]
                       (let [e (d/pull db (vec synth-attrs) eid)]
                         (cond-> []
                           (:arch/component e) (conj [:db/retract eid :arch/component (:db/id (:arch/component e))])
                           (:arch/layer e)     (conj [:db/retract eid :arch/layer (:arch/layer e)])
                           (:sem/category e)   (conj [:db/retract eid :sem/category (:sem/category e)])
                           (:sem/patterns e)   (into (mapv #(vector :db/retract eid :sem/patterns %) (:sem/patterns e)))
                           (:sem/purpose e)    (conj [:db/retract eid :sem/purpose (:sem/purpose e)])))))
             vec)
        comp-retractions (mapv (fn [[eid]] [:db/retractEntity eid]) comp-eids)]
    (into file-retractions comp-retractions)))

(defn retract-synthesis!
  "Retract all component entities and synthesis-assigned file attributes.
   Single atomic transaction to avoid partial retraction states."
  [conn]
  (let [tx (retraction-tx-data (d/db conn))]
    (when (seq tx)
      (d/transact conn {:tx-data tx}))))

;; --- Deterministic component dependency derivation ---

(defn cross-component-edges
  "Query file/imports edges crossing component boundaries.
   Returns set of [from-comp-eid to-comp-eid] pairs."
  [db]
  (->> (d/q '[:find ?fc ?tc
              :where
              [?f1 :file/imports ?f2]
              [?f1 :arch/component ?fc]
              [?f2 :arch/component ?tc]
              [(!= ?fc ?tc)]]
            db)
       set))

(defn component-deps-tx
  "Convert cross-component edge set to additive tx-data for :component/depends-on."
  [edges]
  (let [by-source (group-by first edges)]
    (->> by-source
         (mapv (fn [[src targets]]
                 {:db/id src
                  :component/depends-on (mapv second targets)})))))

(defn derive-component-deps!
  "Derive component dependencies from the import graph and transact.
   Additive — merges with existing LLM-declared deps."
  [conn]
  (let [edges  (cross-component-edges (d/db conn))
        tx     (component-deps-tx edges)]
    (when (seq tx)
      (d/transact conn {:tx-data (conj tx {:db/id "datomic.tx"
                                           :tx/op :derive-deps
                                           :tx/source :deterministic})}))
    {:edges-derived (count edges)}))

;; --- Main orchestration ---

(defn synthesize-repo!
  "Identify components and classify files architecturally.
   `invoke-llm` is (prompt -> {:text string :usage map :resolved-model string}).
   Returns {:components n :files-classified n :elapsed-ms n :usage map}."
  [conn invoke-llm {:keys [meta-db model-id repo-name]}]
  (let [start-ms (System/currentTimeMillis)
        db       (d/db conn)
        data     (prefetch-codebase-data db)]
    (if (seq (:files data))
      (let [meta-db   (or meta-db db)
            template  (artifacts/load-prompt meta-db "synthesize")
            _         (when-not template
                        (throw (ex-info "Synthesize prompt not found — run 'reseed' first"
                                        {:status 400 :message "Synthesize prompt not seeded. Run: noum reseed"})))
            prompt    (render-prompt template (or repo-name "unknown") data)
            _         (log! "synthesize" (str "Synthesizing " (count (:files data))
                                              " files, " (count (:edges data)) " edges"))
            {:keys [text usage resolved-model]} (invoke-llm prompt)
            _         (log! "synthesize" (str "Response length: " (count text) " chars"))
            _         (when (and text (> (count text) 0))
                        (log! "synthesize/tail" (subs text (max 0 (- (count text) 200)))))
            parsed    (parse-response text)]
        (if (and parsed (seq (:components parsed)))
          (let [retract-tx (retraction-tx-data (d/db conn))
                comp-txs   (components->tx-data (:components parsed))
                n-files    (->> (:components parsed) (mapcat :files) distinct count)
                prompt-hash (subs (sha256-hex template) 0 16)
                cost        (llm/estimate-cost (or resolved-model model-id "")
                                               (:input-tokens usage 0)
                                               (:output-tokens usage 0))
                prov-tx    (cond-> {:db/id "datomic.tx"
                                    :tx/op :synthesize
                                    :tx/source :llm
                                    :tx/model (or resolved-model model-id "unknown")
                                    :prov/prompt-hash prompt-hash
                                    :prov/analyzed-at (Date.)}
                             (:input-tokens usage)  (assoc :tx/input-tokens (:input-tokens usage))
                             (:output-tokens usage) (assoc :tx/output-tokens (:output-tokens usage))
                             (pos? cost)            (assoc :tx/cost-usd cost))
                tx-data    (vec (concat retract-tx comp-txs [prov-tx]))]
            (d/transact conn {:tx-data tx-data})
            (let [{:keys [edges-derived]} (derive-component-deps! conn)
                  elapsed (- (System/currentTimeMillis) start-ms)]
              (when (pos? edges-derived)
                (log! "synthesize" (str "Derived " edges-derived
                                        " component dependency edges from import graph")))
              (log! "synthesize" (str "Done. " (count (:components parsed)) " components, "
                                      n-files " files classified ("
                                      elapsed "ms)"))
              {:components       (count (:components parsed))
               :files-classified n-files
               :edges-derived    edges-derived
               :elapsed-ms       elapsed
               :usage            usage}))
          (do (log! "synthesize/error" "Failed to parse synthesis response")
              {:components 0 :files-classified 0
               :elapsed-ms (- (System/currentTimeMillis) start-ms)
               :error "Failed to parse synthesis response"})))
      (do (log! "synthesize" "No analyzed files found — run analyze first")
          {:components 0 :files-classified 0 :elapsed-ms 0}))))
