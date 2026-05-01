(ns noumenon.promotion
  "Content-addressed analysis promotion: skip the LLM when a file's
   blob has been analyzed before with the current prompt + model.

   A 'donor' is a previously-committed analysis whose tx provenance
   matches the current run (same prompt-hash, same model-version) and
   whose target file held the same :file/blob-sha at analyze time. On
   a hit we transact the donor's :sem/* + :arch/* attrs onto the
   recipient file with :prov/promoted-from pointing to the donor tx —
   preserving traceability for staleness audits."
  (:require [datomic.client.api :as d])
  (:import [java.util Date]))

(def promotable-attrs
  "Attributes copied from donor to recipient on promotion."
  [:sem/summary :sem/purpose :sem/tags :sem/complexity :sem/patterns
   :arch/layer :sem/category :prov/confidence :sem/synthesis-hints])

(defn- candidate-pairs
  "Find (file, analyze-tx) pairs in the current DB whose tx provenance
   matches prompt-hash + model-version."
  [db prompt-hash-val model-version]
  (d/q '[:find ?file ?tx
         :in $ ?ph ?mv
         :where
         [?tx :prov/prompt-hash ?ph]
         [?tx :prov/model-version ?mv]
         [?file :sem/summary _ ?tx]]
       db prompt-hash-val model-version))

(defn- snapshot-blob-sha
  "What was this file's :file/blob-sha at the moment of `tx`?"
  [db file-eid tx]
  (-> (d/as-of db tx)
      (d/pull [:file/blob-sha] file-eid)
      :file/blob-sha))

(defn- snapshot-analysis
  "Pull the donor's analysis attrs at the moment of `tx`."
  [db file-eid tx]
  (-> (d/as-of db tx)
      (d/pull (into [:file/path] promotable-attrs) file-eid)))

(defn find-cached-analysis
  "Search the current DB for a previously-analyzed file whose
   :file/blob-sha equaled `blob-sha` and whose analysis-tx's
   :prov/prompt-hash + :prov/model-version match.

   Returns {:donor-tx tx-eid :file/path str <promotable-attrs>} for the
   most recent matching analysis, or nil. Cross-DB lookups (e.g. a
   delta DB asking the trunk DB) are not supported in v1 — restrict
   the caller's `db` to a DB that already contains the prior analysis."
  [db blob-sha prompt-hash-val model-version]
  (when (and blob-sha prompt-hash-val model-version)
    (->> (candidate-pairs db prompt-hash-val model-version)
         (keep (fn [[file tx]]
                 (when (= blob-sha (snapshot-blob-sha db file tx))
                   (let [snap (snapshot-analysis db file tx)]
                     (when (:sem/summary snap)
                       (assoc snap :donor-tx tx))))))
         (sort-by :donor-tx >)
         first)))

(defn promote-tx-data
  "Build tx-data for promoting `donor` analysis onto the file at
   `recipient-path`. Pure. Returns a vector with the file-attr
   assertion + the tx-meta entity (which carries provenance and a
   :prov/promoted-from ref to the donor tx)."
  [recipient-path {:keys [donor-tx] :as donor}]
  (let [donor-attrs (select-keys donor (filter donor promotable-attrs))]
    [(into {:file/path recipient-path} donor-attrs)
     {:db/id              "datomic.tx"
      :tx/op              :promote
      :tx/source          :promoted
      :prov/promoted-from donor-tx
      :prov/analyzed-at   (Date.)}]))

(defn promote!
  "If `donor` is non-nil, transact a promotion for `recipient-path` and
   return {:status :promoted :donor-tx eid}. Otherwise return nil."
  [conn recipient-path donor]
  (when donor
    (d/transact conn {:tx-data (promote-tx-data recipient-path donor)})
    {:status :promoted :donor-tx (:donor-tx donor)}))
