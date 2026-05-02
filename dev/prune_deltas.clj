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

(def deltas-dir
  (str (fs/path (fs/home) ".noumenon" "deltas")))

(def trunk-data-dir
  (str (fs/path (fs/home) ".noumenon" "data" "noumenon")))

(defn parse-name
  "Parse a delta DB name like `<repo>__<safe-branch>__<basis7>` into its
   parts. Returns nil for names that don't match the shape."
  [delta-name]
  (let [parts (str/split delta-name #"__")]
    (when (and (>= (count parts) 3)
               (re-matches #"[a-f0-9]{7}" (last parts)))
      {:repo   (str/join "__" (drop-last 2 parts))
       :branch (last (butlast parts))
       :basis7 (last parts)})))

(defn classify
  "Return :live or :trunk-missing for a parsed delta entry."
  [{:keys [repo]}]
  (if (fs/directory? (fs/path trunk-data-dir repo))
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
  "Walk ~/.noumenon/deltas, classify each entry, return sorted rows."
  []
  (when-not (fs/directory? deltas-dir)
    (println "No deltas directory at" deltas-dir)
    (System/exit 0))
  (->> (fs/list-dir deltas-dir)
       (filter fs/directory?)
       (mapv (fn [path]
               (let [name   (str (fs/file-name path))
                     parsed (parse-name name)]
                 {:name   name
                  :path   (str path)
                  :parsed parsed
                  :status (if parsed (classify parsed) :unparseable)
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
  (let [rows  (list-deltas)
        stale (filter (comp #{:trunk-missing :unparseable} :status) rows)]
    (when (empty? rows)
      (println "No deltas found under" deltas-dir)
      (System/exit 0))
    (print-table rows)
    (if (empty? stale)
      (do (println) (println "All deltas appear live. Nothing to prune."))
      (when (confirm-delete! stale)
        (delete-rows! stale)
        (println "Done.")))))
