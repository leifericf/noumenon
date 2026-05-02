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
  "Search a DB for a previously-analyzed file whose :file/blob-sha equaled
   `blob-sha` and whose analysis-tx's :prov/prompt-hash + :prov/model-version
   match. Returns the most recent donor map, or nil.

   Same-DB case (default): pass only the recipient's `db`. The donor must
   already exist in that DB.

   Cross-DB case: pass the recipient's db plus :donor-db (and optionally
   :donor-db-name) in opts. The candidate scan and snapshot lookups happen
   against `donor-db`; the recipient's db is unused by the lookup itself
   but kept in the signature so callers always pass it (it's the natural
   anchor for the promote!). Returned donor carries :donor-db-name so
   promote-tx-data can record :prov/promoted-from-db-name when crossing."
  ([db blob-sha prompt-hash-val model-version]
   (find-cached-analysis db blob-sha prompt-hash-val model-version {}))
  ([_recipient-db blob-sha prompt-hash-val model-version
    {:keys [donor-db donor-db-name] :as _opts}]
   (when (and donor-db (nil? donor-db-name))
     ;; The two predicates that decide same-DB vs cross-DB are split: the
     ;; lookup uses :donor-db, but `promote-tx-data` keys cross-DB-ness on
     ;; :donor-db-name. Without this pairing, a caller who supplies only
     ;; :donor-db would write `:prov/promoted-from <foreign-tx-id>` — a
     ;; dangling ref. Fail fast at the boundary instead.
     (throw (ex-info "Cross-DB promotion requires :donor-db-name when :donor-db is set"
                     {:donor-db-name donor-db-name})))
   (when (and donor-db-name (nil? donor-db))
     ;; Symmetric guard: with only :donor-db-name set, the lookup falls
     ;; back to the recipient db (search-db = recipient when donor-db
     ;; is nil), so a same-DB hit would be written as cross-DB —
     ;; `:prov/promoted-from-db-name <name>` would record a foreign
     ;; provenance for a donor that actually came from the recipient
     ;; itself. Refuse the partial pairing so the caller has to commit
     ;; to one or the other.
     (throw (ex-info "Cross-DB promotion requires :donor-db when :donor-db-name is set"
                     {:donor-db-name donor-db-name})))
   (when (and blob-sha prompt-hash-val model-version)
     (let [search-db (or donor-db _recipient-db)]
       (->> (candidate-pairs search-db prompt-hash-val model-version)
            (keep (fn [[file tx]]
                    (when (= blob-sha (snapshot-blob-sha search-db file tx))
                      (let [snap (snapshot-analysis search-db file tx)]
                        (when (:sem/summary snap)
                          (cond-> (assoc snap :donor-tx tx)
                            donor-db-name (assoc :donor-db-name donor-db-name)))))))
            (sort-by :donor-tx >)
            first)))))

(defn promote-tx-data
  "Build tx-data for promoting `donor` analysis onto the file at
   `recipient-path`. Pure. Returns a vector with the file-attr
   assertion + the tx-meta entity carrying provenance.

   Same-DB promotion sets :prov/promoted-from to the donor tx ref.
   Cross-DB promotion (when `:donor-db-name` is set on the donor) skips
   :prov/promoted-from — a foreign tx-id would resolve to nothing in the
   recipient DB — and records the donor's db-name in
   :prov/promoted-from-db-name as the breadcrumb."
  [recipient-path {:keys [donor-tx donor-db-name] :as donor}]
  (let [donor-attrs (select-keys donor (filter donor promotable-attrs))
        cross-db?   (some? donor-db-name)]
    [(into {:file/path recipient-path} donor-attrs)
     (cond-> {:db/id            "datomic.tx"
              :tx/op            :promote
              :tx/source        :promoted
              :prov/analyzed-at (Date.)}
       (not cross-db?) (assoc :prov/promoted-from donor-tx)
       cross-db?       (assoc :prov/promoted-from-db-name donor-db-name))]))

(defn promote!
  "If `donor` is non-nil, transact a promotion for `recipient-path` and
   return {:status :promoted :donor-tx eid}. Otherwise return nil."
  [conn recipient-path donor]
  (when donor
    (d/transact conn {:tx-data (promote-tx-data recipient-path donor)})
    {:status :promoted :donor-tx (:donor-tx donor)}))
