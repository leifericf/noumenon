(ns noumenon.sync
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.calls :as calls]
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

(defn- apply-status-line
  "Apply one parsed git --name-status line to the {added modified deleted} accumulator.
   `fields` is the tab-split line with at least 2 elements."
  [acc fields]
  (case (first (first fields))
    \R (let [old-path (nth fields 1)
             new-path (nth fields 2 nil)]
         (cond-> (update acc :deleted conj old-path)
           new-path (update :added conj new-path)))
    \C (if-let [new-path (nth fields 2 nil)]
         (update acc :added conj new-path)
         acc)
    (let [path (nth fields 1)]
      (case (first (first fields))
        \A      (update acc :added    conj path)
        \D      (update acc :deleted  conj path)
        (\M \T) (update acc :modified conj path)
        acc))))

(defn- parse-status-fields [line]
  (let [fields (str/split line #"\t")]
    (when (and (first fields) (>= (count fields) 2))
      fields)))

(defn changed-files
  "Return {:added [...] :modified [...] :deleted [...]} between old-sha and HEAD.
   Each value is a vector of repo-relative file paths.
   Returns nil if old-sha is not a valid 40-char hex SHA. Throws ex-info
   with :status 400 when the SHA is well-formed but does not resolve in
   the repository — otherwise a typo'd basis_sha would silently produce
   an empty diff and the caller would think the operation succeeded."
  [repo-path old-sha]
  (if-not (valid-sha? old-sha)
    (do (log! (str "WARNING: Invalid SHA format, skipping diff: " (pr-str old-sha)))
        nil)
    (let [args (into ["git"] (concat (git/git-dir-args repo-path)
                                     ["diff" "--name-status" "--end-of-options" old-sha "HEAD"]))
          {:keys [exit out err]} (apply shell/sh args)]
      (if-not (zero? exit)
        (let [msg (str "basis SHA " old-sha " does not resolve to a commit in "
                       (str repo-path)
                       (when (seq (str/trim (or err ""))) (str " — " (str/trim err))))]
          (throw (ex-info msg {:status 400 :message msg :user-message msg
                               :sha old-sha :exit exit})))
        (->> (str/split-lines out)
             (keep parse-status-fields)
             (reduce apply-status-line {:added [] :modified [] :deleted []}))))))

;; --- Retraction ---

(def ^:private analyze-file-attrs
  "Attributes from file-level analyze (micro)."
  [:sem/summary :sem/purpose :sem/complexity :sem/tags
   :sem/synthesis-hints :prov/confidence])

(def ^:private synthesize-file-attrs
  "Attributes from codebase-level synthesize (macro)."
  [:arch/layer :sem/category :sem/patterns :arch/component])

(def ^:private analysis-file-attrs
  "All analysis attributes to retract — union of analyze + synthesize."
  (into analyze-file-attrs synthesize-file-attrs))

(def ^:private mutable-file-attrs
  "Attributes to retract on modified/deleted files so the pipeline re-processes them."
  (into [:file/size :file/lines :file/imports] analysis-file-attrs))

(defn- find-file-eid
  "Look up a file entity ID by path. Returns nil if not found."
  [db path]
  (ffirst (d/q '[:find ?e :in $ ?p :where [?e :file/path ?p]] db path)))

(defn- unwrap-ref
  "Extract raw entity ID from a pull ref map, or return v as-is for scalars."
  [v]
  (if (map? v) (:db/id v) v))

(defn- retract-attrs
  "Build retraction tx-data for the given attributes on a file entity."
  [db eid attrs]
  (let [entity (d/pull db attrs eid)]
    (->> attrs
         (mapcat (fn [attr]
                   (let [v (get entity attr)]
                     (cond
                       (nil? v)    nil
                       (map? v)   [[:db/retract eid attr (unwrap-ref v)]]
                       (coll? v)  (mapv (fn [item] [:db/retract eid attr (unwrap-ref item)]) v)
                       :else      [[:db/retract eid attr v]]))))
         vec)))

(defn- retract-code-segments
  "Build retraction tx-data for all code segments belonging to a file entity."
  [db eid]
  (->> (d/q '[:find ?seg :in $ ?file :where [?seg :code/file ?file]] db eid)
       (mapv (fn [[seg-eid]] [:db/retractEntity seg-eid]))))

(defn- build-retraction-tx
  "Build tx-data for retracting file paths using a per-eid tx builder. Pure."
  [db paths build-tx-fn]
  (->> paths
       (keep (fn [path]
               (when-let [eid (find-file-eid db path)]
                 (let [tx (build-tx-fn db eid)]
                   (when (seq tx) tx)))))
       vec))

(defn- transact-retractions!
  "Transact retraction results built by build-retraction-tx. Returns count."
  [conn results]
  (let [tx-data (into [] cat results)]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    (count results)))

(defn retract-stale!
  "Retract mutable attributes and code segments for modified/deleted files.
   Returns count of files actually retracted."
  [conn paths]
  (when (seq paths)
    (let [results (build-retraction-tx (d/db conn) paths
                                       (fn [db eid]
                                         (into (retract-attrs db eid mutable-file-attrs)
                                               (retract-code-segments db eid))))]
      (transact-retractions! conn results))))

(defn retract-analysis!
  "Retract analysis attributes and code segments for the given file paths.
   Does not retract import/enrich attrs (:file/size, :file/lines, :file/imports).
   Returns count of files actually retracted."
  [conn paths]
  (when (seq paths)
    (let [results (build-retraction-tx (d/db conn) paths
                                       (fn [db eid]
                                         (into (retract-attrs db eid analysis-file-attrs)
                                               (retract-code-segments db eid))))]
      (transact-retractions! conn results))))

(def valid-reanalyze-scopes
  "Reanalyze scope strings accepted by every analyze surface (CLI, MCP, HTTP)."
  #{"all" "prompt-changed" "model-changed" "stale"})

(defn prepare-reanalysis!
  "Retract analysis attrs for files matching the reanalyze scope. Returns
   the count of files marked for re-analysis (so the next analyze step
   picks them up via `files-needing-analysis`), or nil if no scope was
   given. Shared across CLI `do-analyze`, MCP `handle-analyze`, and HTTP
   `run-analyze` so all three surfaces agree on the contract."
  [conn db reanalyze {:keys [prompt-hash model-id]}]
  (when reanalyze
    (let [scope (keyword reanalyze)
          files (analyze/files-for-reanalysis db scope {:prompt-hash prompt-hash
                                                        :model-id    model-id})
          paths (mapv :file/path files)
          n     (if (seq paths) (retract-analysis! conn paths) 0)]
      (log! (str "Marked " n " file(s) for re-analysis (scope: " reanalyze ")"))
      n)))

(defn- retract-inbound-imports
  "Build retraction tx-data for :file/imports refs pointing at the given entity."
  [db eid]
  (->> (d/q '[:find ?src :in $ ?target :where [?src :file/imports ?target]] db eid)
       (mapv (fn [[src-eid]] [:db/retract src-eid :file/imports eid]))))

(defn deleted-file-tx
  "Pure tx-data builder for tombstoning a deleted file in a delta DB.
   Uses :file/path identity so the tombstone upserts whether the entity
   already exists in the delta or not. Never used in trunk DBs — trunk
   deletions hard-retract via retract-deleted-files!."
  [path]
  {:file/path path :file/deleted? true})

(defn- assert-no-tombstones
  "Guard: trunk-mode retraction tx must never carry :file/deleted?.
   Tombstones are a delta-DB-only concept."
  [tx-data]
  (assert (not-any? #(and (map? %) (contains? % :file/deleted?)) tx-data)
          "Trunk retraction tx must not contain :file/deleted? — tombstones are delta-only"))

(defn- retract-deleted-files!
  "Trunk DB: hard-retract entire file entities (with inbound imports + code segments).
   Delta DB: upsert tombstones via deleted-file-tx so the delta records the deletion
   without losing the entity. Returns count of files affected."
  [conn paths {:keys [delta-db?]}]
  (when (seq paths)
    (if delta-db?
      (let [tx-data (mapv deleted-file-tx paths)]
        (d/transact conn {:tx-data tx-data})
        (count tx-data))
      (let [results (build-retraction-tx (d/db conn) paths
                                         (fn [db eid]
                                           (-> (retract-inbound-imports db eid)
                                               (into (retract-code-segments db eid))
                                               (conj [:db/retractEntity eid]))))
            tx-data (into [] cat results)]
        (assert-no-tombstones tx-data)
        (when (seq tx-data)
          (d/transact conn {:tx-data tx-data}))
        (count results)))))

(defn- branch-vcs
  "VCS identifier for the branch entity — :perforce for git-p4 clones, :git otherwise."
  [repo-path]
  (if (and (.isDirectory (java.io.File. (str repo-path)))
           (git/p4-clone? repo-path))
    :perforce
    :git))

(defn head-and-branch-tx
  "Pure tx-data builder for HEAD SHA + branch upsert. Self-contained for
   FRESH DBs only — uses tempid \"repo\" and tempid \"branch\".

   This is suitable for the very first sync of a delta or trunk DB. For
   subsequent syncs, use `update-head-and-branch!` which resolves
   existing repo and branch eids before transacting. Tuple identity
   (`:branch/repo+name`) does not auto-unify a fresh tempid with an
   existing entity — Datomic creates a new eid that then conflicts.

   Delta-only opts (basis-sha, parent-host, parent-db-name) are emitted onto
   the branch entity when supplied; nil on trunk. Returns nil if sha missing."
  [{:keys [repo-uri sha branch-name branch-kind branch-vcs
           basis-sha parent-host parent-db-name]}]
  (when sha
    (let [repo-tx   (cond-> {:db/id         "repo"
                             :repo/uri      repo-uri
                             :repo/head-sha sha}
                      branch-name (assoc :repo/branch "branch"))
          branch-tx (cond-> {:db/id        "branch"
                             :branch/repo  "repo"
                             :branch/name  branch-name
                             :branch/kind  branch-kind
                             :branch/vcs   branch-vcs}
                      basis-sha      (assoc :branch/basis-sha basis-sha)
                      parent-host    (assoc :branch/parent-host parent-host)
                      parent-db-name (assoc :branch/parent-db-name parent-db-name))]
      (cond-> [repo-tx]
        branch-name (conj branch-tx)))))

(defn- existing-repo-and-branch-eids
  "Look up the existing repo eid (by :repo/uri) and branch eid (by the
   composite :branch/repo+name tuple) in `db`. Returns [repo-eid branch-eid]
   where each may be nil if the entity doesn't exist yet."
  [db repo-uri branch-name]
  (let [repo-eid   (try
                     (:db/id (d/pull db [:db/id] [:repo/uri repo-uri]))
                     (catch Exception _ nil))
        branch-eid (when (and repo-eid branch-name)
                     (try
                       (:db/id (d/pull db [:db/id]
                                       [:branch/repo+name [repo-eid branch-name]]))
                       (catch Exception _ nil)))]
    [repo-eid branch-eid]))

(defn update-head-and-branch!
  "Idempotently upsert the repo entity and (when supplied) its branch
   entity. Looks up existing eids first so re-syncing the same repo or
   re-running `delta-ensure` doesn't trip the `:branch/repo+name` unique
   constraint — tempids in tuple components don't auto-unify with
   existing entities, so we must resolve them ourselves.

   Returns the resolved repo eid, or nil when sha is missing."
  [conn {:keys [repo-uri sha branch-name branch-kind branch-vcs
                basis-sha parent-host parent-db-name]}]
  (when sha
    (let [[existing-repo existing-branch]
          (existing-repo-and-branch-eids (d/db conn) repo-uri branch-name)
          repo-id    (or existing-repo "repo")
          branch-id  (or existing-branch "branch")
          repo-tx    (cond-> {:db/id         repo-id
                              :repo/uri      repo-uri
                              :repo/head-sha sha}
                       branch-name (assoc :repo/branch branch-id))
          branch-tx  (when branch-name
                       (cond-> {:db/id       branch-id
                                :branch/repo repo-id
                                :branch/name branch-name
                                :branch/kind branch-kind
                                :branch/vcs  branch-vcs}
                         basis-sha      (assoc :branch/basis-sha basis-sha)
                         parent-host    (assoc :branch/parent-host parent-host)
                         parent-db-name (assoc :branch/parent-db-name parent-db-name)))
          tx-data    (cond-> [repo-tx] branch-tx (conj branch-tx))
          {:keys [tempids]} (d/transact conn {:tx-data tx-data})]
      (or existing-repo (get tempids "repo")))))

(defn- update-head-sha!
  "Store the current HEAD SHA on the repo entity and upsert the branch entity.
   Idempotent — uses `update-head-and-branch!` which resolves existing eids
   to avoid `:branch/repo+name` unique-conflicts on re-sync."
  [conn repo-path repo-uri]
  (let [branch-name (git/current-branch-name repo-path)]
    (update-head-and-branch!
     conn
     {:repo-uri    repo-uri
      :sha         (git/head-sha repo-path)
      :branch-name branch-name
      :branch-kind (git/classify-branch-kind branch-name)
      :branch-vcs  (branch-vcs repo-path)})))

;; --- Commit reclassification ---

(defn reclassify-commits!
  "Re-run classify-commit on all stored commits and update any stale :commit/kind values.
   Returns count of commits updated."
  [conn]
  (let [db      (d/db conn)
        commits (d/q '[:find ?e ?msg ?kind
                       :where
                       [?e :commit/message ?msg]
                       [?e :commit/kind ?kind]]
                     db)
        stale   (->> commits
                     (keep (fn [[eid msg old-kind]]
                             (let [new-kind (git/classify-commit msg)]
                               (when (not= old-kind new-kind)
                                 {:db/id eid :commit/kind new-kind}))))
                     vec)]
    (when (seq stale)
      (d/transact conn {:tx-data (conj stale {:db/id "datomic.tx"
                                              :tx/op :import
                                              :tx/source :deterministic})}))
    (count stale)))

(defn backfill-issue-refs!
  "Extract issue references from all commit messages that don't have any yet.
   Returns count of commits updated."
  [conn]
  (let [db       (d/db conn)
        commits  (d/q '[:find ?e ?msg
                        :where
                        [?e :commit/message ?msg]
                        (not [?e :commit/issue-refs])]
                      db)
        updates  (->> commits
                      (keep (fn [[eid msg]]
                              (when-let [refs (git/extract-issue-refs msg)]
                                {:db/id eid :commit/issue-refs refs})))
                      vec)]
    (when (seq updates)
      (d/transact conn {:tx-data (conj updates {:db/id "datomic.tx"
                                                :tx/op :import
                                                :tx/source :deterministic})}))
    (count updates)))

;; --- Sync orchestration ---

(defn- auto-sync-p4!
  "If `repo-path` is a local git-p4 clone, pull from Perforce before reading HEAD."
  [repo-path]
  (when (and (.isDirectory (java.io.File. (str repo-path)))
             (git/p4-clone? repo-path))
    (log! "Detected git-p4 clone, syncing from Perforce...")
    (git/p4-sync! repo-path)))

(defn- compute-changes
  "Resolve {:fresh? :changes} from stored vs. current HEAD.
   Force-pushes / history rewrites surface as a fresh sync rather than an error."
  [repo-path stored]
  (let [fresh? (or (nil? stored) (not (valid-sha? stored)))
        changes (when-not fresh?
                  (try (changed-files repo-path stored)
                       (catch clojure.lang.ExceptionInfo e
                         (log! (str "WARNING: stored HEAD " stored
                                    " no longer resolves; treating as fresh sync ("
                                    (.getMessage e) ")"))
                         nil)))]
    {:fresh?  (or fresh? (nil? changes))
     :changes changes}))

(defn- apply-retractions!
  "Retract analysis on modified files and tombstone/hard-retract deleted files."
  [conn changes opts]
  (when (seq (:modified changes))
    (retract-stale! conn (:modified changes)))
  (when (seq (:deleted changes))
    (retract-deleted-files! conn (:deleted changes)
                            {:delta-db? (:delta-db? opts)})))

(defn- log-incremental-summary! [fresh? changes]
  (when-not fresh?
    (log! (str "Incremental sync: "
               (count (:added changes)) " added, "
               (count (:modified changes)) " modified, "
               (count (:deleted changes)) " deleted"))))

(defn- enrich-needed?
  "Enrichment runs on a fresh import or when any file changed."
  [fresh? changes]
  (or fresh?
      (seq (:added changes))
      (seq (:modified changes))
      (seq (:deleted changes))))

(defn- run-pipeline-stages!
  "Import commits + files, run optional enrich/analyze/calls.
   Returns {:git-r :files-r :reclass-n :issues-n :post-r :analyze-r :calls-r}."
  [conn repo-path repo-uri opts fresh? changes]
  (let [git-r     (git/import-commits! conn repo-path repo-uri)
        reclass-n (reclassify-commits! conn)
        _         (when (pos? reclass-n)
                    (log! (str "Reclassified " reclass-n " commit kinds")))
        issues-n  (backfill-issue-refs! conn)
        _         (when (pos? issues-n)
                    (log! (str "Extracted issue refs for " issues-n " commits")))
        files-r   (files/import-files! conn repo-path repo-uri)
        selector  (select-keys opts [:path :include :exclude :lang])
        post-r    (when (enrich-needed? fresh? changes)
                    (imports/enrich-repo! conn repo-path
                                          (assoc selector :concurrency (or (:concurrency opts) 8))))
        analyze-r (when-let [invoke-llm (:invoke-llm opts)]
                    (analyze/analyze-repo!
                     conn repo-path invoke-llm
                     (assoc selector
                            :meta-db (:meta-db opts)
                            :model-id (:model-id opts)
                            :provider (:provider opts)
                            :concurrency (or (:analyze-concurrency opts) 3)
                            :min-delay-ms 0)))
        calls-r   (when (or post-r analyze-r)
                    (calls/resolve-calls! conn))]
    {:git-r git-r :files-r files-r :reclass-n reclass-n :issues-n issues-n
     :post-r post-r :analyze-r analyze-r :calls-r calls-r}))

(defn- update-result
  [{:keys [git-r files-r reclass-n post-r analyze-r calls-r]} fresh? changes current elapsed-ms]
  (cond-> {:status       (if fresh? :fresh-import :synced)
           :head-sha     current
           :added        (count (:added changes []))
           :modified     (count (:modified changes []))
           :deleted      (count (:deleted changes []))
           :commits      (:commits-imported git-r 0)
           :reclassified reclass-n
           :files        (:files-imported files-r 0)
           :imports      (:imports-resolved post-r 0)
           :elapsed-ms   elapsed-ms}
    analyze-r (assoc :analyzed (:files-analyzed analyze-r 0))
    calls-r   (assoc :calls-resolved (:resolved calls-r 0))))

(defn update-repo!
  "Update the knowledge graph with the current git state.
   Handles both fresh (no database / no stored SHA) and incremental cases.
   Options:
     :analyze?    — also run LLM analysis on changed files (default false)
     :invoke-llm  — LLM invoke function (required if :analyze? true)
     :meta-db     — meta database value (required if :analyze? true)
     :model-id    — model identifier for analysis
     :concurrency — worker count for analyze/enrich"
  [conn repo-path repo-uri opts]
  (auto-sync-p4! repo-path)
  (let [start-ms (System/currentTimeMillis)
        stored   (stored-head-sha (d/db conn))
        current  (git/head-sha repo-path)]
    (if (and stored (= stored current))
      (do (when-not (:quiet? opts)
            (log! "Already up to date" (str "(HEAD " (subs current 0 7) ")")))
          {:status :up-to-date :head-sha current :elapsed-ms 0})
      (let [{:keys [fresh? changes]} (compute-changes repo-path stored)]
        (apply-retractions! conn changes opts)
        (log-incremental-summary! fresh? changes)
        (let [stages   (run-pipeline-stages! conn repo-path repo-uri opts fresh? changes)
              _        (update-head-sha! conn repo-path repo-uri)
              elapsed  (- (System/currentTimeMillis) start-ms)]
          (log! (str "Update complete (" elapsed " ms)"))
          (update-result stages fresh? changes current elapsed))))))
