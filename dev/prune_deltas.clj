(ns prune-deltas
  "Interactively GC stale local delta DBs under ~/.noumenon/deltas/.

   A delta DB is `live` when its parent trunk DB still exists at
   ~/.noumenon/data/noumenon/<repo>/. Otherwise it's `trunk-missing` and
   safe to delete.

   We deliberately do NOT call into the daemon to verify the basis SHA
   still resolves to a real commit — that would couple this task to the
   JVM daemon being up. Rebased deltas are mostly harmless (the local DB
   is throwaway) and a future iteration can add an HTTP-backed check."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

;; Datomic-Local stores DBs under <storage-dir>/<system>/<db-name>/. The
;; daemon uses `:system "noumenon"`, so the actual deltas live one level
;; deeper than ~/.noumenon/deltas/. Walking the parent dir would surface
;; the system dir itself as a single "unparseable" entry — and a `y` at
;; the prompt would then nuke every delta DB on the machine.
(def deltas-dir
  (str (fs/path (fs/home) ".noumenon" "deltas" "noumenon")))

(def trunk-data-dir
  (str (fs/path (fs/home) ".noumenon" "data" "noumenon")))

(defn parse-name
  "Parse a delta DB name like `<repo>__<safe-branch>-<hash6>__<basis7>` into
   its parts. Returns nil for names that don't match the shape.

   Anchored on the trailing `-<hash6>__<basis7>` so branch names that
   themselves contain `__` (e.g. `feat__under`) round-trip correctly. The
   prior split-on-`__` parser misclassified those names — it treated the
   first `__` boundary as the repo/branch separator regardless of how many
   `__` substrings the branch happened to contain, falsely flagging the
   delta as :trunk-missing.

   Pre-disambiguator names (no `-<hash6>` suffix on the branch segment)
   no longer parse — that format predates commit e55e745 and stale
   pre-disambiguator deltas are expected to be re-created rather than
   supported alongside the new format."
  [delta-name]
  (when-let [[_ repo branchhash basis7]
             (re-matches #"(.+?)__(.+)__([a-f0-9]{7})" delta-name)]
    (when-let [[_ branch _hash6]
               (re-matches #"(.+)-([a-f0-9]{6})" branchhash)]
      {:repo   repo
       :branch branch
       :basis7 basis7})))

(defn candidate-repos
  "Possible (repo, branch) splits for a parsed delta entry, ordered by
   repo length descending. Walks the `__` boundaries between the parsed
   repo and branch so that `parse-name`'s branch-favoring heuristic
   (which attributes every `__`-segment to the branch) doesn't lose to
   a real repo basename that itself contains `__`. Without this,
   `my__repo__feat-...__...` parses as repo=my and `my` has no trunk —
   so the delta gets falsely flagged :trunk-missing and offered for
   deletion even though `my__repo` is the actual live trunk."
  [{:keys [repo branch]}]
  (let [parts (when branch (str/split branch #"__"))]
    (if (or (nil? branch) (< (count parts) 2))
      [{:repo repo :branch branch}]
      (->> (range (dec (count parts)) -1 -1)
           (mapv (fn [i]
                   {:repo   (str/join "__" (cons repo (take i parts)))
                    :branch (str/join "__" (drop i parts))}))))))

(defn resolve-against-trunk
  "Pick the candidate split whose repo dir exists under `trunk-data-dir`,
   preferring the longest matching repo. Returns the original parsed entry
   unchanged if no candidate has a trunk dir."
  [parsed]
  (or (->> (candidate-repos parsed)
           (some (fn [c]
                   (when (fs/directory? (fs/path trunk-data-dir (:repo c)))
                     c))))
      parsed))

(defn classify
  "Return :live or :trunk-missing for a parsed delta entry. Walks the
   `__` boundary between parsed repo and branch so a repo basename
   containing `__` (which `parse-name`'s branch-favoring heuristic
   would misattribute to the branch) still classifies correctly."
  [parsed]
  (if (->> (candidate-repos parsed)
           (some #(fs/directory? (fs/path trunk-data-dir (:repo %)))))
    :live
    :trunk-missing))

(defn dir-size-mb
  "Sum file sizes under a directory; 0 if the dir is missing."
  [path]
  (try
    (->> (file-seq (fs/file path))
         (filter #(.isFile ^java.io.File %))
         (map #(.length ^java.io.File %))
         (reduce + 0)
         (#(quot (long %) (* 1024 1024))))
    (catch Exception _ 0)))

(defn list-deltas
  "Walk ~/.noumenon/deltas, classify each entry, return sorted rows.
   When the parser's heuristic split disagrees with what's on disk, the
   row's :parsed is rewritten to the resolved (repo, branch) so the
   table shows the truth instead of the misparse."
  []
  (when-not (fs/directory? deltas-dir)
    (println "No deltas directory at" deltas-dir)
    (System/exit 0))
  (->> (fs/list-dir deltas-dir)
       (filter fs/directory?)
       (mapv (fn [path]
               (let [name   (str (fs/file-name path))
                     parsed (parse-name name)
                     status (if parsed (classify parsed) :unparseable)
                     resolved (when (and parsed (= status :live))
                                (merge parsed (resolve-against-trunk parsed)))]
                 {:name   name
                  :path   (str path)
                  :parsed (or resolved parsed)
                  :status status
                  :size   (dir-size-mb path)})))
       (sort-by (juxt (comp #{:trunk-missing :unparseable :live} :status) (comp - :size)))))

(defn print-table
  "Render rows as a fixed-width table on stdout."
  [rows]
  (println)
  (printf "  %-40s %-30s %-9s %6s  %s%n"
          "repo" "branch" "basis7" "MB" "status")
  (println "  " (apply str (repeat 100 "-")))
  (doseq [{:keys [name parsed size status]} rows]
    (let [{:keys [repo branch basis7]} (or parsed {:repo name :branch "—" :basis7 "—"})]
      (printf "  %-40s %-30s %-9s %6d  %s%n"
              (subs (str repo) 0 (min 40 (count (str repo))))
              (subs (str branch) 0 (min 30 (count (str branch))))
              (str basis7) size (clojure.core/name status)))))

(defn confirm-delete!
  "Print the deletion list, ask y/N, return true iff user typed 'y'."
  [stale-rows]
  (println)
  (println "Will delete" (count stale-rows) "stale delta DBs:")
  (doseq [{:keys [path]} stale-rows] (println "  " path))
  (print "\nDelete? [y/N] ") (flush)
  (= "y" (str/lower-case (or (read-line) ""))))

(defn delete-rows!
  "Recursively delete each row's path."
  [rows]
  (doseq [{:keys [path]} rows]
    (fs/delete-tree path)
    (println "  deleted" path)))

(defn run
  "Entry point for `bb prune-deltas`."
  []
  (let [rows         (list-deltas)
        unparseable  (filter (comp #{:unparseable} :status) rows)
        stale        (filter (comp #{:trunk-missing} :status) rows)]
    (when (empty? rows)
      (println "No deltas found under" deltas-dir)
      (System/exit 0))
    (print-table rows)
    (when (seq unparseable)
      (println)
      (println "Unparseable entries are NOT eligible for automatic deletion —")
      (println "investigate manually before removing:")
      (doseq [{:keys [path]} unparseable] (println "  " path)))
    (if (empty? stale)
      (do (println) (println "All parseable deltas appear live. Nothing to prune."))
      (when (confirm-delete! stale)
        (delete-rows! stale)
        (println "Done.")))))
