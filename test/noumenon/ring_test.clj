(ns noumenon.ring-test
  "End-to-end validation of the import pipeline on the Ring repository.
   Clones Ring if not already present, imports via the CLI entry point,
   and verifies idempotency, status counts, and spot-checks."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.test-helpers :as th]))

;; --- Test infrastructure ---

(def ^:private ring-url "https://github.com/ring-clojure/ring.git")

(def ^:private ring-dir
  (str (.getAbsolutePath (io/file "data" "repos" "ring"))))

(def ^:private db-dir
  (str (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")))
       "/noumenon-ring-test-" (random-uuid)))

(defn- ensure-ring-clone! []
  (th/ensure-git-clone! ring-url ring-dir))

(defn- git-count [& args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" ring-dir args)]
    (when (not= 0 exit)
      (throw (ex-info (str "git command failed: " err) {:args args :exit exit})))
    (-> out str/trim parse-long)))

(def ^:private run-capturing th/run-capturing)

(use-fixtures :once
  (fn [f]
    (ensure-ring-clone!)
    (f)))

;; --- Tests ---

(deftest ring-import-first-time
  (let [result (run-capturing ["import" "--db-dir" db-dir ring-dir])]
    (testing "import succeeds"
      (is (= 0 (:exit result))))
    (testing "stdout contains import counts"
      (is (str/includes? (:stdout result) ":commits-imported"))
      (is (str/includes? (:stdout result) ":files-imported"))
      (is (str/includes? (:stdout result) ":db-path")))))

(deftest ring-import-idempotent
  ;; Ensure imported first
  (run-capturing ["import" "--db-dir" db-dir ring-dir])
  (let [result (run-capturing ["import" "--db-dir" db-dir ring-dir])]
    (testing "re-import succeeds"
      (is (= 0 (:exit result))))
    (testing "no new commits or files imported"
      (is (str/includes? (:stdout result) ":commits-imported 0"))
      (is (str/includes? (:stdout result) ":files-imported 0")))))

(deftest ring-status-matches-git
  (run-capturing ["import" "--db-dir" db-dir ring-dir])
  (let [expected-commits (git-count "rev-list" "--count" "HEAD")
        result           (run-capturing ["status" "--db-dir" db-dir ring-dir])]
    (testing "status succeeds"
      (is (= 0 (:exit result))))
    (testing "commit count matches git"
      (is (= expected-commits (:commits (:result result)))))))

(deftest ring-status-file-count
  ;; Use Datomic directly for file count since git ls-tree line counting is tricky via shell/sh
  (run-capturing ["import" "--db-dir" db-dir ring-dir])
  (let [{:keys [exit out]} (shell/sh "git" "-C" ring-dir "ls-tree" "-r" "--name-only" "HEAD")
        expected-files (when (= 0 exit)
                         (->> (str/split-lines out)
                              (remove str/blank?)
                              count))
        result         (run-capturing ["status" "--db-dir" db-dir ring-dir])]
    (testing "file count matches git ls-tree"
      (is (= expected-files (:files (:result result)))))))

(deftest ring-spot-check-known-file
  (run-capturing ["import" "--db-dir" db-dir ring-dir])
  (let [conn (db/connect-and-ensure-schema db-dir "ring")
        db   (d/db conn)
        ;; Ring's project.clj should exist
        entity (d/pull db '[:file/path :file/ext :file/lang :file/size]
                       [:file/path "project.clj"])]
    (testing "known file exists with correct attributes"
      (is (= "project.clj" (:file/path entity)))
      (is (= "clj" (:file/ext entity)))
      (is (= :clojure (:file/lang entity)))
      (is (pos? (:file/size entity))))))

(deftest ring-spot-check-known-commit
  (run-capturing ["import" "--db-dir" db-dir ring-dir])
  (let [conn (db/connect-and-ensure-schema db-dir "ring")
        db   (d/db conn)
        ;; Get the initial commit
        initial-sha (-> (shell/sh "git" "-C" ring-dir "rev-list" "--max-parents=0" "HEAD")
                        :out str/trim (str/split #"\n") first)]
    (testing "initial commit has expected attributes"
      (let [entity (d/pull db '[:git/sha :git/type :commit/message
                                {:commit/author [:person/name :person/email]}]
                           [:git/sha initial-sha])]
        (is (= :commit (:git/type entity)))
        (is (some? (:commit/message entity)))
        (is (some? (get-in entity [:commit/author :person/email])))))))

(deftest ring-person-deduplication
  (run-capturing ["import" "--db-dir" db-dir ring-dir])
  (let [conn    (db/connect-and-ensure-schema db-dir "ring")
        db      (d/db conn)
        persons (count (d/q '[:find ?e :where [?e :person/email _]] db))
        commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))]
    (testing "fewer persons than commits (deduplication works)"
      (is (< persons commits)))))
