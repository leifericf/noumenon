(ns noumenon.delta-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.delta :as delta]
            [noumenon.test-helpers :as th]))

;; --- Pure helpers ---

(deftest delta-db-name-test
  (testing "encodes repo + branch + short basis"
    (is (= "myrepo__main__abcdef0"
           (delta/delta-db-name "myrepo" "main" "abcdef0123456789"))))
  (testing "sanitizes branch separators for filesystem-safe db-name"
    (is (= "myrepo__feat-branch-aware-graph__1234567"
           (delta/delta-db-name "myrepo" "feat/branch-aware-graph"
                                "1234567abcdef"))))
  (testing "nil branch becomes 'detached'"
    (is (= "r__detached__1234567"
           (delta/delta-db-name "r" nil "1234567abc"))))
  (testing "empty / blank branch becomes 'detached' (would otherwise produce
            an empty path component like 'r____abc1234')"
    (is (= "r__detached__1234567" (delta/delta-db-name "r" "" "1234567abc")))
    (is (= "r__detached__1234567" (delta/delta-db-name "r" "   " "1234567abc"))))
  (testing "dot-only branch becomes 'detached' (would otherwise produce
            literal '.' or '..' dir names that confuse fs tooling)"
    (is (= "r__detached__1234567" (delta/delta-db-name "r" "." "1234567abc")))
    (is (= "r__detached__1234567" (delta/delta-db-name "r" ".." "1234567abc"))))
  (testing "path-escape sequences sanitize to single-component name; chars
            other than dots/separators (e.g. 'etc') stay so the result is
            informative, not 'detached'. Path traversal can't escape because
            the result is a single dir component, not a multi-segment path."
    (is (= "r__..-..-..-etc__1234567"
           (delta/delta-db-name "r" "../../../etc" "1234567abc"))))
  (testing "branch with only separator chars becomes 'detached'"
    (is (= "r__detached__1234567" (delta/delta-db-name "r" "///" "1234567abc")))))

(deftest delta-storage-dir-test
  (testing "default is ~/.noumenon/deltas"
    (is (= (str (System/getProperty "user.home") "/.noumenon/deltas")
           (delta/delta-storage-dir {}))))
  (testing "explicit override wins"
    (is (= "/tmp/test-deltas"
           (delta/delta-storage-dir {:delta-storage-dir "/tmp/test-deltas"})))))

;; --- diff-tx ---

(def ^:private fixture-lstree
  {"src/a.clj" {:path "src/a.clj" :sha "aaa1111111111111111111111111111111111111"
                :size 100}
   "src/b.clj" {:path "src/b.clj" :sha "bbb1111111111111111111111111111111111111"
                :size 200}})

(deftest diff-tx-empty
  (testing "no changes → only tx metadata"
    (let [tx (delta/diff-tx {:added [] :modified [] :deleted []
                             :lstree-by-path {} :line-counts {}})]
      (is (= 1 (count tx)))
      (is (= :import (:tx/op (first tx)))))))

(deftest diff-tx-added
  (testing "added path produces dir + file tx with blob-sha"
    (let [tx (delta/diff-tx {:added ["src/a.clj"]
                             :modified [] :deleted []
                             :lstree-by-path fixture-lstree
                             :line-counts {"src/a.clj" 10}})
          file-entry (some #(when (= "src/a.clj" (:file/path %)) %) tx)]
      (is (= "aaa1111111111111111111111111111111111111"
             (:file/blob-sha file-entry)))
      (is (= 10 (:file/lines file-entry))))))

(deftest diff-tx-deleted
  (testing "deleted path produces tombstone via sync/deleted-file-tx"
    (let [tx (delta/diff-tx {:added [] :modified []
                             :deleted ["src/gone.clj"]
                             :lstree-by-path {} :line-counts {}})
          tombstone (some #(when (= "src/gone.clj" (:file/path %)) %) tx)]
      (is (= true (:file/deleted? tombstone))))))

(deftest diff-tx-mixed
  (testing "added + modified + deleted all present"
    (let [tx (delta/diff-tx {:added ["src/a.clj"]
                             :modified ["src/b.clj"]
                             :deleted ["src/old.clj"]
                             :lstree-by-path fixture-lstree
                             :line-counts {}})
          paths (into #{} (keep :file/path) tx)]
      (is (contains? paths "src/a.clj"))
      (is (contains? paths "src/b.clj"))
      (is (contains? paths "src/old.clj")))))

;; --- ensure-delta-db! integration ---
;; In-memory storage avoids touching the real ~/.noumenon/deltas tree.

(deftest ensure-delta-db-test
  (testing "creates an in-memory delta DB and applies schema"
    (let [conn (delta/ensure-delta-db! (System/getProperty "user.dir")
                                       "abcdef0123456789012345678901234567890abc"
                                       {:delta-storage-dir :mem
                                        :branch-name "test-branch"})]
      (is (some? conn))
      ;; Branch schema must be present
      (is (some? (d/pull (d/db conn) '[*] :branch/name)))
      ;; Tombstone schema must be present
      (is (some? (d/pull (d/db conn) '[*] :file/deleted?))))))

;; --- update-delta! integration ---
;; Uses the noumenon repo itself as the source. Picks a known commit as basis
;; and verifies the resulting delta has only diffed files.

(def ^:private noumenon-repo (System/getProperty "user.dir"))

(defn- conn-for-test []
  (th/make-test-conn "delta-test"))

(deftest update-delta-end-to-end
  (testing "delta against an early commit produces files-only-changed and parent links"
    (let [conn      (conn-for-test)
          ;; First commit on the branch — wide diff against current HEAD.
          basis-sha "6f5d226"
          ;; Need a full 40-char SHA — resolve via git rev-parse
          full-sha  (-> (shell/sh "git" "-C" noumenon-repo
                                  "rev-parse" basis-sha)
                        :out str/trim)
          result    (delta/update-delta! conn noumenon-repo full-sha
                                         {:parent-host "noumenon.example"
                                          :parent-db-name "noumenon-trunk"})
          db        (d/db conn)]
      (testing "result map carries basis + head + counts"
        (is (= :synced (:status result)))
        (is (= full-sha (:basis-sha result)))
        (is (>= (+ (:added result 0) (:modified result 0) (:deleted result 0)) 1)))
      (testing "branch entity has parent-host + parent-db-name + basis-sha"
        (let [[basis host db-name]
              (first (d/q '[:find ?b ?h ?n
                            :where
                            [?e :branch/basis-sha ?b]
                            [?e :branch/parent-host ?h]
                            [?e :branch/parent-db-name ?n]]
                          db))]
          (is (= full-sha basis))
          (is (= "noumenon.example" host))
          (is (= "noumenon-trunk" db-name))))
      (testing "delta DB only contains files that diff between basis and HEAD"
        ;; The delta should NOT contain every file in the repo — only the touched ones.
        (let [delta-files (d/q '[:find ?p :where [?e :file/path ?p]] db)
              repo-files  (-> (shell/sh "git" "-C" noumenon-repo
                                        "ls-tree" "-r" "--name-only" "HEAD")
                              :out str/split-lines count)]
          (is (< (count delta-files) repo-files)
              "Delta should contain fewer files than the full tree"))))))
