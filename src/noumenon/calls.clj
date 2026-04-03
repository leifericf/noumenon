(ns noumenon.calls
  "Resolve :code/call-names (strings) to :code/calls (entity refs).
   Deterministic post-processing — no LLM calls."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.util :refer [log!]]))

;; --- Index building ---

(defn build-name-index
  "Build segment name lookup indexes from the database.
   Returns {:by-file {file-eid {name #{seg-eid}}}
            :global  {name #{seg-eid}}}."
  [db]
  (let [rows (d/q '[:find ?seg ?name ?file
                    :where
                    [?seg :code/name ?name]
                    [?seg :code/file ?file]]
                  db)]
    (reduce (fn [acc [seg-eid name file-eid]]
              (-> acc
                  (update-in [:by-file file-eid name] (fnil conj #{}) seg-eid)
                  (update-in [:global name] (fnil conj #{}) seg-eid)))
            {:by-file {} :global {}}
            rows)))

(defn- build-import-index
  "Build file→imported-files index.
   Returns {file-eid #{imported-file-eid ...}}."
  [db]
  (->> (d/q '[:find ?f ?imported
              :where [?f :file/imports ?imported]]
            db)
       (reduce (fn [acc [f imported]]
                 (update acc f (fnil conj #{}) imported))
               {})))

;; --- Resolution ---

(defn- strip-qualifier
  "Strip namespace/module qualifier from a name: ns/foo -> foo, mod.Bar -> Bar."
  [name-str]
  (let [slash-idx (str/last-index-of name-str "/")
        dot-idx   (str/last-index-of name-str ".")]
    (cond
      slash-idx (subs name-str (inc slash-idx))
      dot-idx   (subs name-str (inc dot-idx))
      :else     nil)))

(defn- unique-match
  "Return the single element if coll has exactly one, else nil."
  [coll]
  (when (= 1 (count coll)) (first coll)))

(defn resolve-call
  "Resolve a call-name string to a segment entity ID.
   Priority: same-file exact → same-file stripped → imported exact → imported stripped.
   Returns seg-eid or nil (skips ambiguous matches)."
  [call-name source-file-eid {:keys [by-file]} import-index]
  (or
   ;; Same file — exact match
   (unique-match (get-in by-file [source-file-eid call-name]))
   ;; Same file — stripped qualifier
   (when-let [bare (strip-qualifier call-name)]
     (unique-match (get-in by-file [source-file-eid bare])))
   ;; Imported files — exact match
   (let [imported (get import-index source-file-eid)]
     (or
      (unique-match
       (->> imported
            (mapcat #(get-in by-file [% call-name]))
            (remove nil?)
            set))
      ;; Imported files — stripped qualifier
      (when-let [bare (strip-qualifier call-name)]
        (unique-match
         (->> imported
              (mapcat #(get-in by-file [% bare]))
              (remove nil?)
              set)))))))

;; --- Batch resolution ---

(defn resolve-all-calls
  "Resolve all :code/call-names to :code/calls refs.
   Returns {:tx-data [...] :stats {:resolved n :ambiguous n :not-found n}}."
  [db]
  (let [name-idx   (build-name-index db)
        import-idx (build-import-index db)
        rows       (d/q '[:find ?seg ?file ?call-name
                          :where
                          [?seg :code/call-names ?call-name]
                          [?seg :code/file ?file]]
                        db)
        results    (reduce (fn [acc [seg-eid file-eid call-name]]
                             (if-let [target (resolve-call call-name file-eid name-idx import-idx)]
                               (-> acc
                                   (update-in [:by-seg seg-eid] (fnil conj #{}) target)
                                   (update :resolved inc))
                               (update acc :unresolved inc)))
                           {:by-seg {} :resolved 0 :unresolved 0}
                           rows)
        tx-data    (->> (:by-seg results)
                        (mapv (fn [[seg-eid targets]]
                                {:db/id seg-eid
                                 :code/calls (vec targets)})))]
    {:tx-data tx-data
     :stats   (select-keys results [:resolved :unresolved])}))

(defn resolve-calls!
  "Resolve call-name strings to entity refs and transact :code/calls.
   Returns stats map."
  [conn]
  (let [{:keys [tx-data stats]} (resolve-all-calls (d/db conn))]
    (when (seq tx-data)
      (d/transact conn {:tx-data (conj tx-data
                                       {:db/id "datomic.tx"
                                        :tx/op :resolve-calls
                                        :tx/source :deterministic})}))
    (log! "resolve-calls" (str "Resolved " (:resolved stats 0)
                               " calls, " (:unresolved stats 0) " unresolved"))
    stats))
