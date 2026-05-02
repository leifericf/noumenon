(ns noumenon.delta
  "Local delta DB materialization.

   A delta DB is a sparse Datomic database that captures a working branch's
   changes vs a trunk basis SHA. It stores only files that were added,
   modified, or deleted between the basis and the branch HEAD — added/modified
   files are upserted, deleted files are marked :file/deleted? true.

   Federation (Phase E) overlays the delta onto trunk: trunk's query is run
   with :exclude_paths covering every :file/path in the delta, then the
   delta's query result is concatenated.

   Delta DBs are throwaway by design — schema mismatch or basis drift means
   wipe and rebuild, never migrate."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]]))

(def ^:private default-deltas-subdir
  ".noumenon/deltas")

(defn delta-storage-dir
  "Resolve the storage directory for delta DBs.
   :delta-storage-dir in opts wins; otherwise ~/.noumenon/deltas."
  [opts]
  (or (:delta-storage-dir opts)
      (str (System/getProperty "user.home") "/" default-deltas-subdir)))

(defn- sanitize-branch
  "Filesystem-safe branch label. Replaces non-`[a-zA-Z0-9._-]` chars with
   `-`. Falls back to `detached` for nil, blank, or dot-only inputs (which
   would otherwise produce empty / `.` / `..` directory names — the latter
   resolve to parent dirs in tools that aren't expecting them as literal
   path components)."
  [branch-name]
  (let [trimmed (some-> branch-name str/trim)
        cleaned (when trimmed (str/replace trimmed #"[^a-zA-Z0-9._-]" "-"))]
    (if (or (str/blank? cleaned)
            (re-matches #"\.+" cleaned)
            (re-matches #"-+" cleaned))
      "detached"
      cleaned)))

(defn- branch-disambiguator
  "First 6 chars of sha256(branch-name) — appended to the sanitized name
   so two real branches that sanitize to the same string (e.g. `feat/foo`
   and `feat-foo` both → `feat-foo`) get DIFFERENT delta DBs.

   Without this, branch-switching between the two would silently
   overwrite each other's diff in the same shared delta DB.

   The branch is trimmed before hashing — `sanitize-branch` already trims,
   so hashing the raw input would produce different hashes for `\"foo\"`
   and `\"foo \"` and create duplicate delta DBs for a single logical
   branch when a caller forgets to trim."
  [branch-name]
  (subs (util/sha256-hex (or (some-> branch-name str/trim) "")) 0 6))

(defn delta-db-name
  "Compose a Datomic db-name encoding repo + branch + basis short-SHA.
   Format: `<repo>__<safe-branch>-<branch-hash6>__<basis7>`.

   Branch separators (e.g. '/') are replaced with '-'; blank or dot-only
   branch names fall back to `detached` so the db-name is filesystem-safe.
   The 6-char hash of the original branch name disambiguates branches that
   would otherwise sanitize to the same label."
  [repo-name branch-name basis-sha]
  (let [short-basis (subs basis-sha 0 (min 7 (count basis-sha)))]
    (str repo-name "__"
         (sanitize-branch branch-name) "-" (branch-disambiguator branch-name)
         "__" short-basis)))

(defn ensure-delta-db!
  "Ensure a delta DB exists for the given repo + current branch + basis-sha.
   Returns a connection. Idempotent — re-opens an existing DB.

   Routes through `db/get-or-create-conn` so the conn (and its schema-load
   tx) is cached per process. The previous direct call to
   `connect-and-ensure-schema` re-transacted every schema file on every
   ensure call, growing the delta's db.log on every read-shaped HTTP
   request even when nothing changed."
  [repo-path basis-sha opts]
  (let [repo-name   (util/derive-db-name repo-path)
        branch-name (or (:branch-name opts) (git/current-branch-name repo-path))
        db-name     (delta-db-name repo-name branch-name basis-sha)
        storage-dir (delta-storage-dir opts)]
    (log! (str "Ensuring delta DB " db-name " at " storage-dir))
    (db/get-or-create-conn storage-dir db-name)))

(defn diff-tx
  "Pure tx-data builder for a delta sync.
   `lstree-by-path` maps path → parsed ls-tree entry (so we can read sha/size).
   `line-counts` maps path → line count.
   Added + modified paths upsert as full file entities (with dir hierarchy).
   Deleted paths upsert as tombstones via sync/deleted-file-tx."
  [{:keys [added modified deleted lstree-by-path line-counts]}]
  (let [touched-paths (vec (concat added modified))
        touched-files (keep lstree-by-path touched-paths)
        dirs          (when (seq touched-paths) (files/paths->dirs touched-paths))
        dir-tx        (mapv files/dir->tx-data (or dirs []))
        file-tx       (mapv #(files/file->tx-data % line-counts) touched-files)
        delete-tx     (mapv sync/deleted-file-tx (or deleted []))
        tx-meta       {:db/id "datomic.tx" :tx/op :import :tx/source :deterministic}]
    (cond-> []
      (seq dir-tx)    (into dir-tx)
      (seq file-tx)   (into file-tx)
      (seq delete-tx) (into delete-tx)
      :always         (conj tx-meta))))

(defn- delta-up-to-date?
  "True when the delta DB already reflects (basis-sha, current-HEAD).
   Lets read-shaped callers (`/api/query-federated`) short-circuit so a
   no-op refresh doesn't write a tx on every read."
  [db basis-sha current-head]
  (let [stored-head  (sync/stored-head-sha db)
        stored-basis (ffirst (d/q '[:find ?b :where [_ :branch/basis-sha ?b]] db))]
    (and stored-head  (= stored-head current-head)
         stored-basis (= stored-basis basis-sha))))

(defn update-delta!
  "Sync a delta DB to record changes between basis-sha and current HEAD.
   :parent-host and :parent-db-name in opts link the delta to its trunk.
   Idempotent — re-running with the same basis applies the same diff and
   updates the existing branch entity in place via `update-head-and-branch!`.

   Short-circuits to :up-to-date when basis-sha + HEAD haven't moved since
   the last sync; without this guard, a read-only call to query-federated
   would still write a head-and-branch tx on every invocation."
  [conn repo-path basis-sha opts]
  (let [start-ms (System/currentTimeMillis)
        current  (git/head-sha repo-path)]
    (if (delta-up-to-date? (d/db conn) basis-sha current)
      (do (log! (str "Delta already up to date (basis " (subs basis-sha 0 7)
                     " → HEAD " (subs current 0 7) ")"))
          {:status     :up-to-date
           :added      0 :modified 0 :deleted 0
           :basis-sha  basis-sha
           :head-sha   current
           :elapsed-ms 0})
      (let [changes        (sync/changed-files repo-path basis-sha)
            all-ls-tree    (files/parse-ls-tree (files/git-ls-tree repo-path))
            lstree-by-path (into {} (map (juxt :path identity)) all-ls-tree)
            line-counts    (files/git-line-counts repo-path)
            tx             (diff-tx {:added          (:added changes [])
                                     :modified       (:modified changes [])
                                     :deleted        (:deleted changes [])
                                     :lstree-by-path lstree-by-path
                                     :line-counts    line-counts})
            branch-name    (or (:branch-name opts) (git/current-branch-name repo-path))]
        (when (and tx (> (count tx) 1))
          (d/transact conn {:tx-data tx}))
        (sync/update-head-and-branch!
         conn
         {:repo-uri       repo-path
          :sha            current
          :branch-name    branch-name
          :branch-kind    (git/classify-branch-kind branch-name)
          :branch-vcs     :git
          :basis-sha      basis-sha
          :parent-host    (:parent-host opts)
          :parent-db-name (:parent-db-name opts)})
        (let [elapsed (- (System/currentTimeMillis) start-ms)]
          (log! (str "Delta sync: " (count (:added changes [])) " added, "
                     (count (:modified changes [])) " modified, "
                     (count (:deleted changes [])) " deleted "
                     "(basis " (subs basis-sha 0 7) " → " (subs current 0 7) ", "
                     elapsed " ms)"))
          {:status     :synced
           :added      (count (:added changes []))
           :modified   (count (:modified changes []))
           :deleted    (count (:deleted changes []))
           :basis-sha  basis-sha
           :head-sha   current
           :elapsed-ms elapsed})))))
