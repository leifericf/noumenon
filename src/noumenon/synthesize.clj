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
            [noumenon.util :as util :refer [log! sha256-hex]])
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
  (let [formatted (format-prefetch data)
        bindings  {"repo-name"        (str/replace repo-name #"[^a-zA-Z0-9._/-]" "_")
                   "file-count"       (str (count (:files data)))
                   "directory-tree"   (:directory-tree formatted)
                   "file-summaries"   (:file-summaries formatted)
                   "import-graph"     (:import-graph formatted)
                   "tag-distribution" (:tag-distribution formatted)}]
    (str/replace template #"\{\{([^}]+)\}\}"
                 (fn [[match key]] (get bindings key match)))))

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

(defn- validate-merge-component
  "Validate a component from the merge response (has :source-components instead of :files)."
  [{:keys [name summary purpose layer category patterns
           complexity subsystem source-components depends-on]}]
  (when (and (string? name) (seq name) (seq source-components))
    (cond-> {:name name :source-components (vec (filter string? source-components))}
      (string? summary)              (assoc :summary (clamp summary))
      (string? purpose)              (assoc :purpose (clamp purpose))
      (valid-layer layer)            (assoc :layer layer)
      (valid-category category)      (assoc :category category)
      (seq (filter valid-patterns patterns)) (assoc :patterns (vec (filter valid-patterns patterns)))
      (valid-complexity complexity)  (assoc :complexity complexity)
      (string? subsystem)            (assoc :subsystem subsystem)
      (seq depends-on)               (assoc :depends-on (vec (filter string? depends-on))))))

(defn- parse-merge-response
  "Parse and validate a merge synthesis response (source-components instead of files)."
  [text]
  (when text
    (let [cleaned (analyze/strip-markdown-fences text)
          parsed  (try (edn/read-string {:readers {}} cleaned)
                       (catch Exception e
                         (log! "synthesize/parse-merge" (.getMessage e))
                         nil))]
      (when (map? parsed)
        {:components (->> (:components parsed) (keep validate-merge-component) vec)}))))

(defn parse-response
  "Parse and validate the LLM synthesis response."
  [text]
  (when text
    (let [cleaned (analyze/strip-markdown-fences text)
          parsed  (try (edn/read-string {:readers {}} cleaned)
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
  (let [synth-attrs [:arch/component :arch/layer :sem/category :sem/patterns]
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
                           (:sem/patterns e)   (into (mapv #(vector :db/retract eid :sem/patterns %) (:sem/patterns e)))))))
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

(def ^:private single-call-threshold
  "Max file count for single-call synthesis. Above this, use hierarchical."
  250)

(def ^:private min-partition-size
  "Directories with fewer files than this get merged into an 'other' bucket."
  5)

(def ^:private max-partition-size
  "Partitions larger than this get split by second-level directory."
  150)

(defn- invoke-and-parse
  "Render prompt, invoke LLM, and parse the response. Retries once on parse failure.
   Returns {:parsed map :usage map :resolved-model string}."
  [invoke-llm template repo-name data]
  (let [prompt (render-prompt template (or repo-name "unknown") data)
        _      (log! "synthesize" (str "Synthesizing " (count (:files data))
                                       " files, " (count (:edges data)) " edges"))
        invoke-once (fn []
                      (let [{:keys [text usage resolved-model]} (invoke-llm prompt)]
                        (log! "synthesize" (str "Response length: " (count text) " chars"))
                        (when (seq text)
                          (log! "synthesize/tail" (subs text (max 0 (- (count text) 200)))))
                        {:parsed (parse-response text) :usage usage
                         :resolved-model resolved-model}))
        r1 (invoke-once)]
    (if (and (:parsed r1) (seq (:components (:parsed r1))))
      r1
      (do (log! "synthesize" "Retrying (unparseable response)...")
          (let [r2 (invoke-once)]
            (-> r2
                (assoc :resolved-model (:resolved-model r1))
                (update :usage #(llm/sum-usage (:usage r1) %))))))))

;; --- Hierarchical map-reduce ---

(defn- dir-at-depth
  "Extract directory path at a given depth from a file path."
  [path depth]
  (let [parts (str/split path #"/")]
    (if (> (count parts) depth)
      (str/join "/" (take depth parts))
      (str/join "/" (butlast parts)))))

(defn- split-recursively
  "Recursively split a file group by increasing directory depth until all
   partitions are <= max-partition-size. Returns {dir-name [files]}."
  [dir-name files depth]
  (if (<= (count files) max-partition-size)
    {dir-name files}
    (let [sub (group-by #(dir-at-depth (:path %) depth) files)]
      (if (= 1 (count sub))
        ;; Can't split further (flat directory) — keep as-is
        {dir-name files}
        (reduce-kv
         (fn [acc k v]
           (merge acc (split-recursively k v (inc depth))))
         {} sub)))))

(defn- partition-by-directory
  "Group files by directory, recursively splitting until all partitions
   are <= max-partition-size. Merge tiny groups into an '(other)' bucket."
  [files]
  (let [groups   (group-by #(dir-at-depth (:path %) 1) files)
        expanded (reduce-kv
                  (fn [acc dir-name dir-files]
                    (merge acc (split-recursively dir-name dir-files 2)))
                  {} groups)
        small    (into {} (filter #(< (count (val %)) min-partition-size) expanded))
        large    (into {} (remove #(< (count (val %)) min-partition-size) expanded))
        other    (vec (mapcat val small))]
    (cond-> large
      (seq other) (assoc "(other)" other))))

(defn- render-partition-prompt
  "Render the partition-level synthesis prompt."
  [template repo-name partition-name partition-files full-data]
  (let [formatted (format-prefetch {:files partition-files
                                    :tags  (:tags full-data)
                                    :edges (:edges full-data)
                                    :dirs  (:dirs full-data)})
        bindings  {"repo-name"        (str/replace (str repo-name) #"[^a-zA-Z0-9._/-]" "_")
                   "partition-name"   (str partition-name)
                   "file-count"       (str (count partition-files))
                   "directory-tree"   (:directory-tree formatted)
                   "file-summaries"   (format-file-summaries partition-files)
                   "import-graph"     (:import-graph formatted)
                   "tag-distribution" (:tag-distribution formatted)}]
    (str/replace template #"\{\{([^}]+)\}\}"
                 (fn [[match key]] (get bindings key match)))))

(defn- format-partition-components
  "Format partition-level components for the merge prompt."
  [partition-results]
  (->> partition-results
       (map (fn [{:keys [partition-name components]}]
              (str "### Partition: " partition-name "\n"
                   (->> components
                        (map (fn [{:keys [name summary files depends-on]}]
                               (str "- **" name "** — " summary
                                    "\n  Files: " (str/join ", " (take 10 files))
                                    (when (> (count files) 10) (str " (+" (- (count files) 10) " more)"))
                                    (when (seq depends-on) (str "\n  Depends on: " (str/join ", " depends-on))))))
                        (str/join "\n")))))
       (str/join "\n\n")))

(defn- render-merge-prompt
  "Render the merge-level synthesis prompt."
  [template repo-name total-files partition-results full-data]
  (let [formatted (format-prefetch full-data)
        bindings  {"repo-name"             (str/replace (str repo-name) #"[^a-zA-Z0-9._/-]" "_")
                   "file-count"            (str total-files)
                   "directory-tree"        (:directory-tree formatted)
                   "import-graph"          (:import-graph formatted)
                   "partition-components"  (format-partition-components partition-results)}]
    (str/replace template #"\{\{([^}]+)\}\}"
                 (fn [[match key]] (get bindings key match)))))

(defn- invoke-partition
  "Synthesize components for one directory partition. Returns {:partition-name :components :usage}."
  [invoke-llm template repo-name partition-name partition-files full-data]
  (let [prompt (render-partition-prompt template repo-name partition-name partition-files full-data)
        _      (log! "synthesize/partition" (str partition-name ": prompt=" (count prompt) " chars"))
        invoke-once (fn []
                      (let [{:keys [text usage resolved-model]} (invoke-llm prompt)]
                        (log! "synthesize/partition" (str partition-name ": response=" (count (str text)) " chars"))
                        {:parsed (parse-response text) :usage usage :resolved-model resolved-model}))
        r1 (invoke-once)
        result (if (and (:parsed r1) (seq (:components (:parsed r1))))
                 r1
                 (do (log! "synthesize/partition" (str partition-name ": retry (no components parsed)"))
                     (invoke-once)))]
    {:partition-name partition-name
     :components     (get-in result [:parsed :components] [])
     :usage          (:usage result)
     :resolved-model (:resolved-model result)}))

(defn- invoke-merge
  "Merge partition-level components into final component map. Returns {:parsed :usage}."
  [invoke-llm template repo-name total-files partition-results full-data]
  (let [prompt (render-merge-prompt template repo-name total-files partition-results full-data)
        invoke-once (fn []
                      (let [{:keys [text usage resolved-model]} (invoke-llm prompt)]
                        (log! "synthesize" (str "Merge response: " (count text) " chars"))
                        (when (seq text)
                          (log! "synthesize/tail" (subs text (max 0 (- (count text) 200)))))
                        {:parsed (parse-merge-response text) :usage usage :resolved-model resolved-model}))
        r1 (invoke-once)]
    (if (and (:parsed r1) (seq (:components (:parsed r1))))
      r1
      (do (log! "synthesize" "Merge retry...")
          (let [r2 (invoke-once)]
            (update r2 :usage #(llm/sum-usage (:usage r1) %)))))))

(defn- synthesize-hierarchical
  "Hierarchical map-reduce synthesis for large repos.
   Level 1: synthesize per top-level directory.
   Level 2: merge partition results into final component map."
  [invoke-llm data {:keys [meta-db repo-name on-progress]}]
  (let [meta-db         (or meta-db (throw (ex-info "meta-db required" {})))
        partition-tmpl  (artifacts/load-prompt meta-db "synthesize-partition")
        merge-tmpl      (artifacts/load-prompt meta-db "synthesize-merge")
        _               (log! "synthesize" (str "partition-tmpl: " (if partition-tmpl
                                                                     (str (count partition-tmpl) " chars")
                                                                     "NOT FOUND")))
        _               (log! "synthesize" (str "merge-tmpl: " (if merge-tmpl
                                                                 (str (count merge-tmpl) " chars")
                                                                 "NOT FOUND")))
        _               (when-not (and partition-tmpl merge-tmpl)
                          (throw (ex-info "Hierarchical prompts not found — run 'reseed' first"
                                          {:status 400})))
        partitions      (partition-by-directory (:files data))
        n-partitions    (count partitions)
        _               (log! "synthesize" (str (count (:files data)) " files across "
                                                n-partitions " partitions (hierarchical mode)"))
        ;; Level 1: per-partition synthesis
        partition-results
        (vec (map-indexed
              (fn [i [dir-name dir-files]]
                (when on-progress
                  (on-progress {:phase :partition :current (inc i) :total n-partitions
                                :directory dir-name}))
                (log! "synthesize" (str "[" (inc i) "/" n-partitions "] "
                                        dir-name " (" (count dir-files) " files)"))
                (let [result (invoke-partition invoke-llm partition-tmpl repo-name
                                               dir-name dir-files data)]
                  (log! "synthesize" (str "[" (inc i) "/" n-partitions "] "
                                          dir-name " — " (count (:components result)) " components"))
                  result))
              (sort-by key partitions)))
        partition-usage (reduce llm/sum-usage llm/zero-usage (map :usage partition-results))
        total-partition-comps (reduce + (map #(count (:components %)) partition-results))
        ;; Level 2: merge
        _  (log! "synthesize" (str "merging " total-partition-comps " partition components..."))
        _  (when on-progress
             (on-progress {:phase :merge :current 1 :total 1 :components total-partition-comps}))
        merge-result (invoke-merge invoke-llm merge-tmpl repo-name
                                   (count (:files data)) partition-results data)
        total-usage  (llm/sum-usage partition-usage (:usage merge-result))
        ;; Reconstruct file lists from source-components mapping
        part-comp-index (into {}
                              (for [pr partition-results
                                    c  (:components pr)]
                                [(:name c) (:files c)]))
        resolved (when-let [comps (get-in merge-result [:parsed :components])]
                   {:components
                    (mapv (fn [c]
                            (let [sources  (:source-components c)
                                  files    (if (seq sources)
                                             (vec (distinct (mapcat #(get part-comp-index % []) sources)))
                                             (:files c))]
                              (-> c (assoc :files files) (dissoc :source-components))))
                          comps)})]
    (log! "synthesize" (str "merge resolved: " (count (:components resolved)) " components, "
                            (count (distinct (mapcat :files (:components resolved)))) " files"))
    {:parsed         (or resolved (:parsed merge-result))
     :usage          total-usage
     :resolved-model (:resolved-model merge-result)
     :mode           :hierarchical
     :partitions     n-partitions}))

(defn- build-provenance-tx
  "Build the provenance transaction entity for a synthesis run."
  [template model-id resolved-model usage]
  (let [prompt-hash (subs (sha256-hex template) 0 16)
        cost        (llm/estimate-cost (or resolved-model model-id "")
                                       (:input-tokens usage 0)
                                       (:output-tokens usage 0))]
    (cond-> {:db/id "datomic.tx"
             :tx/op :synthesize
             :tx/source :llm
             :tx/model (or resolved-model model-id "unknown")
             :prov/prompt-hash prompt-hash
             :prov/analyzed-at (Date.)}
      (:input-tokens usage)  (assoc :tx/input-tokens (:input-tokens usage))
      (:output-tokens usage) (assoc :tx/output-tokens (:output-tokens usage))
      (pos? cost)            (assoc :tx/cost-usd cost))))

(defn- transact-and-finalize!
  "Retract old synthesis, transact new components, derive deps. Returns result map."
  [conn parsed model-id resolved-model usage start-ms mode-info]
  (if-not (and parsed (seq (:components parsed)))
    (do (log! "synthesize/error" "Failed to parse synthesis response")
        (merge {:components 0 :files-classified 0
                :elapsed-ms (- (System/currentTimeMillis) start-ms)
                :error "Failed to parse synthesis response"}
               mode-info))
    (let [template-str (pr-str mode-info)
          retract-tx (retraction-tx-data (d/db conn))
          _          (when (seq retract-tx)
                       (d/transact conn {:tx-data retract-tx}))
          create-tx  (vec (concat (components->tx-data (:components parsed))
                                  [(build-provenance-tx template-str model-id
                                                        resolved-model usage)]))]
      (d/transact conn {:tx-data create-tx})
      (let [{:keys [edges-derived]} (derive-component-deps! conn)
            n-files (count (distinct (mapcat :files (:components parsed))))
            elapsed (- (System/currentTimeMillis) start-ms)]
        (when (pos? edges-derived)
          (log! "synthesize" (str "Derived " edges-derived
                                  " component dependency edges from import graph")))
        (log! "synthesize" (str "Done. " (count (:components parsed)) " components, "
                                n-files " files classified (" elapsed "ms)"))
        (merge {:components       (count (:components parsed))
                :files-classified n-files
                :edges-derived    edges-derived
                :elapsed-ms       elapsed
                :usage            usage}
               mode-info)))))

(defn synthesize-repo!
  "Identify components and classify files architecturally.
   `invoke-llm` is (prompt -> {:text string :usage map :resolved-model string}).
   opts: {:meta-db :model-id :repo-name :on-progress}
   Returns {:components n :files-classified n :elapsed-ms n :usage map :mode kw}."
  [conn invoke-llm {:keys [meta-db model-id repo-name on-progress]}]
  (let [start-ms (System/currentTimeMillis)
        db       (d/db conn)
        data     (prefetch-codebase-data db)]
    (if-not (seq (:files data))
      (do (log! "synthesize" "No analyzed files found — run analyze first")
          {:components 0 :files-classified 0 :elapsed-ms 0})
      (let [meta-db (or meta-db db)
            n-files (count (:files data))]
        (if (<= n-files single-call-threshold)
          ;; Small repo: single-call path (unchanged behavior)
          (let [template (artifacts/load-prompt meta-db "synthesize")
                _        (when-not template
                           (throw (ex-info "Synthesize prompt not found — run 'reseed' first"
                                           {:status 400 :message "Run: noum reseed"})))
                {:keys [parsed usage resolved-model]}
                (invoke-and-parse invoke-llm template repo-name data)]
            (transact-and-finalize! conn parsed model-id resolved-model usage start-ms
                                    {:mode :single-call}))
          ;; Large repo: hierarchical map-reduce
          (let [{:keys [parsed usage resolved-model mode partitions]}
                (synthesize-hierarchical invoke-llm data
                                         {:meta-db meta-db :repo-name repo-name
                                          :on-progress on-progress})]
            (transact-and-finalize! conn parsed model-id resolved-model usage start-ms
                                    {:mode mode :partitions partitions})))))))
