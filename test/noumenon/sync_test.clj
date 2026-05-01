(ns noumenon.sync-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [noumenon.sync :as sync]))

(deftest valid-sha?-test
  (testing "accepts valid 40-char hex SHA"
    (is (sync/valid-sha? "abc123def456789012345678901234567890abcd"))
    (is (sync/valid-sha? "0000000000000000000000000000000000000000")))
  (testing "rejects invalid SHAs"
    (is (not (sync/valid-sha? nil)))
    (is (not (sync/valid-sha? "")))
    (is (not (sync/valid-sha? "abc123")))
    (is (not (sync/valid-sha? "ABCDEF0000000000000000000000000000000000")))
    (is (not (sync/valid-sha? "abc123def456789012345678901234567890abcd; rm -rf /")))
    (is (not (sync/valid-sha? "--option-injection")))))

(def ^:private test-sha "abc123def456789012345678901234567890abcd")

(deftest changed-files-rename-test
  (testing "rename lines produce :deleted for old path and :added for new path"
    (with-redefs [shell/sh (constantly {:exit 0 :out "R100\told/file.clj\tnew/file.clj\n"})]
      (let [result (sync/changed-files "/tmp" test-sha)]
        (is (= ["old/file.clj"] (:deleted result)))
        (is (= ["new/file.clj"] (:added result)))
        (is (= [] (:modified result))))))
  (testing "mixed statuses parsed correctly"
    (with-redefs [shell/sh (constantly
                            {:exit 0
                             :out (str "A\tsrc/new.clj\n"
                                       "M\tsrc/changed.clj\n"
                                       "D\tsrc/gone.clj\n"
                                       "R100\tsrc/old.clj\tsrc/renamed.clj\n")})]
      (let [result (sync/changed-files "/tmp" test-sha)]
        (is (= ["src/new.clj" "src/renamed.clj"] (:added result)))
        (is (= ["src/changed.clj"] (:modified result)))
        (is (= ["src/gone.clj" "src/old.clj"] (:deleted result))))))
  (testing "copy lines produce :added for new path only"
    (with-redefs [shell/sh (constantly {:exit 0 :out "C100\tsrc/orig.clj\tsrc/copy.clj\n"})]
      (let [result (sync/changed-files "/tmp" test-sha)]
        (is (= ["src/copy.clj"] (:added result)))
        (is (= [] (:deleted result)))
        (is (= [] (:modified result))))))
  (testing "invalid SHA returns nil"
    (is (nil? (sync/changed-files "/tmp" "not-a-sha")))))

(deftest deleted-file-tx-test
  (testing "produces tombstone upsert keyed on :file/path identity"
    (is (= {:file/path "src/gone.clj" :file/deleted? true}
           (sync/deleted-file-tx "src/gone.clj")))))

(deftest head-and-branch-tx-test
  (testing "nil sha returns nil"
    (is (nil? (sync/head-and-branch-tx {:repo-uri "u" :sha nil :branch-name "main"}))))
  (testing "no branch-name → only head-sha tx, no :repo/branch pointer"
    (let [tx (sync/head-and-branch-tx {:repo-uri "u" :sha "abc"})]
      (is (= 1 (count tx)))
      (is (= {:db/id "repo" :repo/uri "u" :repo/head-sha "abc"} (first tx)))))
  (testing "branch-name present → repo gets pointer + branch entity transacted"
    (let [tx (sync/head-and-branch-tx
              {:repo-uri    "u"
               :sha         "abc"
               :branch-name "main"
               :branch-kind :trunk
               :branch-vcs  :git})]
      (is (= 2 (count tx)))
      (is (= {:db/id "repo" :repo/uri "u" :repo/head-sha "abc" :repo/branch "branch"}
             (first tx)))
      (is (= {:db/id        "branch"
              :branch/repo  "repo"
              :branch/name  "main"
              :branch/kind  :trunk
              :branch/vcs   :git}
             (second tx)))))
  (testing "delta opts populate basis-sha + parent fields on the branch entity"
    (let [tx (sync/head-and-branch-tx
              {:repo-uri        "u"
               :sha             "dev"
               :branch-name     "feat/x"
               :branch-kind     :feature
               :branch-vcs      :git
               :basis-sha       "trunk-sha"
               :parent-host     "noumenon.example"
               :parent-db-name  "myrepo"})
          branch (second tx)]
      (is (= "trunk-sha" (:branch/basis-sha branch)))
      (is (= "noumenon.example" (:branch/parent-host branch)))
      (is (= "myrepo" (:branch/parent-db-name branch)))))
  (testing "delta opts omitted on trunk → no basis/parent attrs emitted"
    (let [branch (-> (sync/head-and-branch-tx
                      {:repo-uri "u" :sha "abc" :branch-name "main"
                       :branch-kind :trunk :branch-vcs :git})
                     second)]
      (is (not (contains? branch :branch/basis-sha)))
      (is (not (contains? branch :branch/parent-host)))
      (is (not (contains? branch :branch/parent-db-name))))))
