(ns noumenon.git
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]
           [java.util Date]))

;; Git log format: %x01 = record separator, %x00 = field separator.
;; %B (raw body) can contain arbitrary text including blank lines,
;; so we bracket it with %x00 to make parsing unambiguous.
;; --name-only appends changed files after the format output.
(def ^:private git-log-format
  "%x01%H%x00%P%x00%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI%x00%B%x00")

(def ^:private max-message-length 4096)

(defn truncate
  "Clamp string s to at most n characters."
  [s n]
  (cond
    (nil? s)          nil
    (> (count s) n)   (subs s 0 n)
    :else             s))

(defn- parse-iso-instant [s]
  (->> s
       (.parse DateTimeFormatter/ISO_OFFSET_DATE_TIME)
       Instant/from
       Date/from))

(defn- parse-files [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- parse-record [text]
  (let [fields (str/split text #"\x00" -1)]
    {:sha             (nth fields 0)
     :parent-shas     (let [p (nth fields 1)]
                        (if (str/blank? p) [] (str/split p #" ")))
     :author-name     (nth fields 2)
     :author-email    (nth fields 3)
     :authored-at     (parse-iso-instant (nth fields 4))
     :committer-name  (nth fields 5)
     :committer-email (nth fields 6)
     :committed-at    (parse-iso-instant (nth fields 7))
     :message         (str/trim (nth fields 8))
     :changed-files   (parse-files (nth fields 9 ""))}))

(defn parse-commits
  "Parse raw git log output (produced by git-log) into a sequence of commit maps."
  [log-text]
  (if (str/blank? log-text)
    []
    (->> (str/split log-text #"\x01")
         (remove str/blank?)
         (mapv parse-record))))

(defn commit->tx-data
  "Convert a parsed commit map into a Datomic tx-data vector.
   Uses tempids for persons and files so Datomic can resolve references
   within a single transaction (upserts via :db.unique/identity).
   Parent commits use lookup refs since they exist from prior transactions."
  [{:keys [sha parent-shas author-name author-email authored-at
           committer-name committer-email committed-at message changed-files]}]
  (let [author-tid    (str "person-" author-email)
        committer-tid (str "person-" committer-email)
        file-tids     (mapv #(str "file-" %) changed-files)
        commit        (cond-> {:git/sha             sha
                               :git/type            :commit
                               :commit/message      (truncate message max-message-length)
                               :commit/author       author-tid
                               :commit/authored-at  authored-at
                               :commit/committer    committer-tid
                               :commit/committed-at committed-at}
                        (seq parent-shas)
                        (assoc :commit/parents
                               (mapv #(vector :git/sha %) parent-shas))
                        (seq file-tids)
                        (assoc :commit/changed-files file-tids))]
    (into [{:db/id author-tid :person/email author-email :person/name author-name}
           {:db/id committer-tid :person/email committer-email :person/name committer-name}
           commit
           {:db/id "datomic.tx" :tx/op :import :tx/source :deterministic}]
          (map (fn [[path tid]] {:db/id tid :file/path path}))
          (map vector changed-files file-tids))))

(defn git-log
  "Shell out to git log on the given repo path. Returns raw output string.
   Throws on non-zero exit (not a git repo, path doesn't exist, etc.)."
  [repo-path]
  (let [{:keys [exit out err]} (shell/sh "git" "-C" (str repo-path) "log" "--reverse"
                                         (str "--format=" git-log-format) "--name-only")]
    (when (not= 0 exit)
      (throw (ex-info (str "git log failed: " (str/trim err))
                      {:exit exit :repo-path (str repo-path)})))
    out))

;; --- Import orchestration ---

(defn- imported-shas
  "Return the set of commit SHAs already in the database."
  [db]
  (->> (d/q '[:find ?sha :where [?e :git/sha ?sha] [?e :git/type :commit]] db)
       (into #{} (map first))))

(defn unimported-commits
  "Filter parsed commits to only those not yet in the database."
  [db commits]
  (let [existing (imported-shas db)]
    (into [] (remove #(existing (:sha %))) commits)))

(defn import-commits!
  "Import git history from repo-path into Datomic via conn.
   Parses git log, filters already-imported commits, transacts one per tx.
   Returns a summary map with :commits-imported, :commits-skipped, :elapsed-ms."
  [conn repo-path]
  (let [start-ms  (System/currentTimeMillis)
        raw       (git-log repo-path)
        all       (parse-commits raw)
        to-import (unimported-commits (d/db conn) all)
        skipped   (- (count all) (count to-import))]
    (when (and (empty? all) (seq raw))
      (binding [*out* *err*]
        (println "Warning: git log produced output but no commits were parsed")))
    (when (empty? all)
      (binding [*out* *err*]
        (println "Warning: no commits found in" (str repo-path))))
    (doseq [commit to-import]
      (d/transact conn {:tx-data (commit->tx-data commit)}))
    (let [elapsed (- (System/currentTimeMillis) start-ms)]
      (binding [*out* *err*]
        (println (str "Imported " (count to-import) " commits, "
                      "skipped " skipped " already present. "
                      "(" elapsed " ms)")))
      {:commits-imported (count to-import)
       :commits-skipped  skipped
       :elapsed-ms       elapsed})))
