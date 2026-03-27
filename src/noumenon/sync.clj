(ns noumenon.sync
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.imports :as imports]
            [noumenon.util :refer [log!]]))

;; --- Staleness detection ---

(defn stored-head-sha
  "Return the stored HEAD SHA for the repo, or nil if not yet recorded."
  [db]
  (ffirst (d/q '[:find ?sha :where [_ :repo/head-sha ?sha]] db)))

(defn stale?
  "True if the database has no stored HEAD SHA or it differs from current HEAD."
  [db repo-path]
  (let [stored  (stored-head-sha db)
        current (git/head-sha repo-path)]
    (or (nil? stored) (not= stored current))))

;; --- Git diff parsing ---

(def ^:private sha-pattern
  "Matches a 40-character lowercase hex SHA."
  #"[0-9a-f]{40}")

(defn valid-sha?
  "True if s is a well-formed 40-char hex SHA."
  [s]
  (boolean (and (string? s) (re-matches sha-pattern s))))

(defn changed-files
  "Return {:added [...] :modified [...] :deleted [...]} between old-sha and HEAD.
   Each value is a vector of repo-relative file paths.
   Returns nil if old-sha is not a valid 40-char hex SHA."
  [repo-path old-sha]
  (if-not (valid-sha? old-sha)
    (do (log! "WARNING" (str "Invalid SHA format, skipping diff: " (pr-str old-sha)))
        nil)
    (let [{:keys [exit out]}
          (shell/sh "git" "-C" (str repo-path)
                    "diff" "--name-status" old-sha "HEAD")]
      (when (zero? exit)
        (->> (str/split-lines out)
             (remove str/blank?)
             (reduce (fn [acc line]
                       (let [fields (str/split line #"\t")
                             status (first fields)]
                         (if (and status (>= (count fields) 2))
                           (if (= \R (first status))
                             (let [old-path (nth fields 1)
                                   new-path (nth fields 2 nil)]
                               (cond-> (update acc :deleted conj old-path)
                                 new-path (update :added conj new-path)))
                             (let [path (nth fields 1)
                                   k    (case (first status)
                                          \A :added
                                          \D :deleted
                                          (\M \T) :modified
                                          nil)]
                               (if k (update acc k conj path) acc)))
                           acc)))
                     {:added [] :modified [] :deleted []}))))))

;; --- Retraction ---

(def ^:private mutable-file-attrs
  "Attributes to retract on modified/deleted files so the pipeline re-processes them."
  [:file/size :file/lines :file/imports
   :sem/summary :sem/purpose :sem/tags :sem/complexity
   :sem/patterns :sem/category :sem/dependencies
   :arch/layer :arch/subsystem
   :prov/confidence])

(defn- unwrap-ref
  "Extract raw entity ID from a pull ref map, or return v as-is for scalars."
  [v]
  (if (map? v) (:db/id v) v))

(defn- retract-file-attrs
  "Build retraction tx-data for mutable attributes on a file entity."
  [db eid]
  (let [entity (d/pull db mutable-file-attrs eid)]
    (->> mutable-file-attrs
         (mapcat (fn [attr]
                   (let [v (get entity attr)]
                     (cond
                       (nil? v)  nil
                       (coll? v) (mapv (fn [item] [:db/retract eid attr (unwrap-ref item)]) v)
                       :else     [[:db/retract eid attr (unwrap-ref v)]]))))
         vec)))

(defn- retract-code-segments
  "Build retraction tx-data for all code segments belonging to a file entity."
  [db eid]
  (->> (d/q '[:find ?seg :in $ ?file :where [?seg :code/file ?file]] db eid)
       (mapv (fn [[seg-eid]] [:db/retractEntity seg-eid]))))

(defn retract-stale!
  "Retract mutable attributes and code segments for modified/deleted files.
   Returns count of files actually retracted."
  [conn paths]
  (when (seq paths)
    (let [db      (d/db conn)
          results (->> paths
                       (keep (fn [path]
                               (when-let [eid (ffirst (d/q '[:find ?e :in $ ?p
                                                             :where [?e :file/path ?p]]
                                                           db path))]
                                 (let [tx (into (retract-file-attrs db eid)
                                                (retract-code-segments db eid))]
                                   (when (seq tx) tx)))))
                       vec)
          tx-data (into [] cat results)]
      (when (seq tx-data)
        (d/transact conn {:tx-data tx-data}))
      (count results))))

(defn- retract-deleted-files!
  "Retract entire file entities for deleted files. Returns count actually retracted."
  [conn paths]
  (when (seq paths)
    (let [db      (d/db conn)
          results (->> paths
                       (keep (fn [path]
                               (when-let [eid (ffirst (d/q '[:find ?e :in $ ?p
                                                             :where [?e :file/path ?p]]
                                                           db path))]
                                 (into (retract-code-segments db eid)
                                       [[:db/retractEntity eid]]))))
                       vec)
          tx-data (into [] cat results)]
      (when (seq tx-data)
        (d/transact conn {:tx-data tx-data}))
      (count results))))

(defn- update-head-sha!
  "Store the current HEAD SHA on the repo entity."
  [conn repo-path repo-uri]
  (let [sha (git/head-sha repo-path)]
    (when sha
      (d/transact conn {:tx-data [{:repo/uri repo-uri :repo/head-sha sha}]}))))

;; --- Sync orchestration ---

(defn update-repo!
  "Update the knowledge graph with the current git state.
   Handles both fresh (no database / no stored SHA) and incremental cases.
   Options:
     :analyze?    — also run LLM analysis on changed files (default false)
     :invoke-llm  — LLM invoke function (required if :analyze? true)
     :model-id    — model identifier for analysis
     :concurrency — worker count for analyze/enrich"
  [conn repo-path repo-uri opts]
  (let [start-ms (System/currentTimeMillis)
        db       (d/db conn)
        stored   (stored-head-sha db)
        current  (git/head-sha repo-path)]
    (if (and stored (= stored current))
      (do (log! "Already up to date" (str "(HEAD " (subs current 0 7) ")"))
          {:status :up-to-date :head-sha current :elapsed-ms 0})
      (let [fresh?     (or (nil? stored) (not (valid-sha? stored)))
            changes    (when-not fresh? (changed-files repo-path stored))
            _          (when (seq (:modified changes))
                         (retract-stale! conn (:modified changes)))
            _          (when (seq (:deleted changes))
                         (retract-deleted-files! conn (:deleted changes)))
            _          (when-not fresh?
                         (log! (str "Incremental sync: "
                                    (count (:added changes)) " added, "
                                    (count (:modified changes)) " modified, "
                                    (count (:deleted changes)) " deleted")))
            git-r      (git/import-commits! conn repo-path repo-uri)
            files-r    (files/import-files! conn repo-path repo-uri)
            post-r     (imports/enrich-repo! conn repo-path
                                             {:concurrency (or (:concurrency opts) 8)})
            analyze-r  (when (:analyze? opts)
                         (let [invoke-llm (:invoke-llm opts)]
                           (when invoke-llm
                             (analyze/analyze-repo!
                              conn repo-path invoke-llm
                              {:model-id     (:model-id opts)
                               :concurrency  (or (:analyze-concurrency opts) 3)
                               :min-delay-ms 0}))))
            _          (update-head-sha! conn repo-path repo-uri)
            elapsed    (- (System/currentTimeMillis) start-ms)]
        (log! (str "Update complete (" elapsed " ms)"))
        (cond-> {:status      (if fresh? :fresh-import :synced)
                 :head-sha    current
                 :added       (count (:added changes []))
                 :modified    (count (:modified changes []))
                 :deleted     (count (:deleted changes []))
                 :commits     (:commits-imported git-r 0)
                 :files       (:files-imported files-r 0)
                 :imports     (:imports-resolved post-r 0)
                 :elapsed-ms  elapsed}
          analyze-r (assoc :analyzed (:files-analyzed analyze-r 0)))))))
