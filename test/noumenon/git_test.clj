(ns noumenon.git-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.git :as git]
            [noumenon.util :as util]))

;; --- Test helpers ---

(defn- make-record
  "Build a git log record matching the format produced by git-log with --numstat.
   `files` is a seq of file paths (or nil for no changed files).
   Each file gets a default numstat line of '10\t5\tfilename'."
  [sha parents author-name author-email authored-at
   committer-name committer-email committed-at message files]
  (str "\u0001"
       sha "\u0000"
       parents "\u0000"
       author-name "\u0000"
       author-email "\u0000"
       authored-at "\u0000"
       committer-name "\u0000"
       committer-email "\u0000"
       committed-at "\u0000"
       message "\n\u0000"
       (when (seq files)
         (str "\n" (str/join "\n" (map #(str "10\t5\t" %) files)) "\n"))))

(def ^:private iso-ts "2024-01-15T10:30:00+00:00")

;; --- Tier 0: Pure function tests ---

(deftest truncate-test
  (testing "short strings unchanged"
    (is (= "hello" (util/truncate "hello" 10))))
  (testing "exact length unchanged"
    (is (= "hello" (util/truncate "hello" 5))))
  (testing "long strings truncated"
    (is (= "hel" (util/truncate "hello" 3))))
  (testing "nil returns nil"
    (is (nil? (util/truncate nil 10)))))

(deftest parse-single-commit
  (let [log     (make-record "abc1234" "" "Leif" "leif@example.com" iso-ts
                             "Leif" "leif@example.com" iso-ts
                             "feat: add parser" ["src/core.clj" "test/core_test.clj"])
        commits (git/parse-commits log)]
    (testing "parses one commit"
      (is (= 1 (count commits))))
    (testing "extracts fields correctly"
      (let [c (first commits)]
        (is (= "abc1234" (:sha c)))
        (is (= "Leif" (:author-name c)))
        (is (= "leif@example.com" (:author-email c)))
        (is (= "feat: add parser" (:message c)))
        (is (= ["src/core.clj" "test/core_test.clj"] (:changed-files c)))
        (is (= [] (:parent-shas c)))
        (is (inst? (:authored-at c)))
        (is (inst? (:committed-at c)))))))

(deftest parse-multiple-commits
  (let [log     (str (make-record "aaa" "" "A" "a@x.com" "2024-01-01T00:00:00+00:00"
                                  "A" "a@x.com" "2024-01-01T00:00:00+00:00"
                                  "first" ["a.txt"])
                     (make-record "bbb" "aaa" "B" "b@x.com" "2024-01-02T00:00:00+00:00"
                                  "B" "b@x.com" "2024-01-02T00:00:00+00:00"
                                  "second" ["b.txt"]))
        commits (git/parse-commits log)]
    (is (= 2 (count commits)))
    (is (= "aaa" (:sha (first commits))))
    (is (= "bbb" (:sha (second commits))))
    (is (= ["aaa"] (:parent-shas (second commits))))))

(deftest parse-merge-commit
  (let [log     (make-record "merge123" "abc1234 def5678" "Leif" "leif@example.com"
                             iso-ts "Leif" "leif@example.com" iso-ts
                             "Merge branch 'feature'" [])
        commits (git/parse-commits log)]
    (is (= ["abc1234" "def5678"] (:parent-shas (first commits))))))

(deftest parse-empty-log
  (is (= [] (git/parse-commits "")))
  (is (= [] (git/parse-commits nil)))
  (is (= [] (git/parse-commits "   "))))

(deftest parse-commit-body-with-null-bytes
  (testing "null bytes in commit body do not shift numstat field"
    (let [;; Build a record manually with a null byte embedded in the body.
          ;; make-record places message between %x00 delimiters, so an
          ;; embedded \x00 would create an extra split field.
          log     (str "\u0001"
                       "deadbeef" "\u0000"
                       "" "\u0000"
                       "Author" "\u0000"
                       "a@x.com" "\u0000"
                       iso-ts "\u0000"
                       "Author" "\u0000"
                       "a@x.com" "\u0000"
                       iso-ts "\u0000"
                       "body with\u0000embedded null\n\u0000"
                       "\n10\t5\tsrc/a.clj\n")
          commits (git/parse-commits log)
          c       (first commits)]
      (is (= 1 (count commits)))
      (is (= "deadbeef" (:sha c)))
      (is (= "body with\u0000embedded null" (:message c)))
      (is (= ["src/a.clj"] (:changed-files c)))
      (is (= 10 (:additions c)))
      (is (= 5 (:deletions c))))))

(deftest parse-commit-no-changed-files
  (let [log     (make-record "sha1" "" "A" "a@x.com" iso-ts "A" "a@x.com" iso-ts "init" nil)
        commits (git/parse-commits log)]
    (is (= [] (:changed-files (first commits))))))

(deftest message-truncation-in-tx-data
  (let [long-msg (apply str (repeat 5000 "x"))
        commit   {:sha "abc" :parent-shas [] :author-name "A" :author-email "a@x.com"
                  :authored-at #inst "2024-01-01" :committer-name "A" :committer-email "a@x.com"
                  :committed-at #inst "2024-01-01" :message long-msg :changed-files []}
        tx-data  (git/commit->tx-data "test-repo" commit)
        entity   (first (filter :git/sha tx-data))]
    (is (= 4096 (count (:commit/message entity))))))

(deftest tx-data-shape
  (let [commit  {:sha "abc123" :parent-shas ["parent1"]
                 :author-name "Alice" :author-email "alice@x.com"
                 :authored-at #inst "2024-01-01"
                 :committer-name "Bob" :committer-email "bob@x.com"
                 :committed-at #inst "2024-01-01"
                 :message "test commit" :changed-files ["src/a.clj"]}
        tx-data (git/commit->tx-data "test-repo" commit)
        commit-entity (first (filter :git/sha tx-data))]
    (testing "commit entity"
      (is (= "abc123" (:git/sha commit-entity)))
      (is (= :commit (:git/type commit-entity)))
      (is (= "test commit" (:commit/message commit-entity)))
      (is (= "person-alice@x.com" (:commit/author commit-entity)))
      (is (= "person-bob@x.com" (:commit/committer commit-entity)))
      (is (= [[:git/sha "parent1"]] (:commit/parents commit-entity)))
      (is (= ["file-src/a.clj"] (:commit/changed-files commit-entity))))
    (testing "person entities with tempids"
      (is (some #(and (= "person-alice@x.com" (:db/id %))
                      (= "alice@x.com" (:person/email %))
                      (= "Alice" (:person/name %)))
                tx-data))
      (is (some #(and (= "person-bob@x.com" (:db/id %))
                      (= "bob@x.com" (:person/email %))
                      (= "Bob" (:person/name %)))
                tx-data)))
    (testing "file entity stub with tempid"
      (is (some #(and (= "file-src/a.clj" (:db/id %))
                      (= "src/a.clj" (:file/path %)))
                tx-data)))
    (testing "tx metadata"
      (is (some #(and (= "datomic.tx" (:db/id %))
                      (= :import (:tx/op %))
                      (= :deterministic (:tx/source %)))
                tx-data)))))

(deftest tx-data-no-parents-no-files
  (let [commit  {:sha "root" :parent-shas []
                 :author-name "A" :author-email "a@x.com"
                 :authored-at #inst "2024-01-01"
                 :committer-name "A" :committer-email "a@x.com"
                 :committed-at #inst "2024-01-01"
                 :message "initial" :changed-files []}
        tx-data (git/commit->tx-data "test-repo" commit)
        entity  (first (filter :git/sha tx-data))]
    (is (nil? (:commit/parents entity)))
    (is (nil? (:commit/changed-files entity)))))

;; --- Tier 1: Integration tests (in-memory Datomic + real git repo) ---

(defn- test-conn []
  (db/connect-and-ensure-schema :mem (str "git-test-" (random-uuid))))

(defn- repo-commit-count
  "Get the total commit count for the repo at the given path."
  [repo-path]
  (-> (shell/sh "git" "-C" repo-path "rev-list" "--count" "HEAD")
      :out str/trim parse-long))

(def ^:private repo-path (System/getProperty "user.dir"))

(deftest import-noumenon-repo
  (let [expected (repo-commit-count repo-path)
        conn     (test-conn)
        result   (git/import-commits! conn repo-path repo-path)
        db       (d/db conn)]
    (testing "imports correct number of commits"
      (is (= expected (:commits-imported result)))
      (is (= 0 (:commits-skipped result))))
    (testing "commits are queryable"
      (is (= expected
             (ffirst (d/q '[:find (count ?e) :where [?e :git/type :commit]] db)))))
    (testing "known commit has expected attributes"
      (let [initial-sha (-> (shell/sh "git" "-C" repo-path "rev-list" "--max-parents=0" "HEAD")
                            :out str/trim)
            entity      (d/pull db '[:git/sha :git/type :commit/message
                                     {:commit/author [:person/email]}]
                                [:git/sha initial-sha])]
        (is (= :commit (:git/type entity)))
        (is (some? (:commit/message entity)))
        (is (some? (get-in entity [:commit/author :person/email])))))))

(deftest import-idempotency
  (let [conn    (test-conn)
        _       (git/import-commits! conn repo-path repo-path)
        count1  (ffirst (d/q '[:find (count ?e) :where [?e :git/type :commit]] (d/db conn)))
        result2 (git/import-commits! conn repo-path repo-path)
        count2  (ffirst (d/q '[:find (count ?e) :where [?e :git/type :commit]] (d/db conn)))]
    (testing "second import produces no new commits"
      (is (= 0 (:commits-imported result2))))
    (testing "second import skips all"
      (is (= count1 (:commits-skipped result2))))
    (testing "commit count unchanged"
      (is (= count1 count2)))))

(deftest import-resume
  (let [conn    (test-conn)
        raw     (git/git-log repo-path)
        all     (git/parse-commits raw)
        first-c (first all)
        _       (d/transact conn {:tx-data (git/commit->tx-data repo-path first-c)})
        result  (git/import-commits! conn repo-path repo-path)
        total   (ffirst (d/q '[:find (count ?e) :where [?e :git/type :commit]] (d/db conn)))]
    (testing "skipped the already-imported commit"
      (is (= 1 (:commits-skipped result))))
    (testing "imported the rest"
      (is (= (dec (count all)) (:commits-imported result))))
    (testing "total count is correct"
      (is (= (count all) total)))))

(deftest person-deduplication
  (let [conn    (test-conn)
        _       (git/import-commits! conn repo-path repo-path)
        db      (d/db conn)
        persons (d/q '[:find ?email :where [?e :person/email ?email]] db)
        commits (ffirst (d/q '[:find (count ?e) :where [?e :git/type :commit]] db))]
    (testing "fewer person entities than commits"
      (is (< (count persons) commits)))))

;; --- URL detection and repo name extraction ---

(deftest git-url?-recognizes-urls
  (testing "https URLs"
    (is (git/git-url? "https://github.com/ring-clojure/ring.git"))
    (is (git/git-url? "https://github.com/foo/bar")))
  (testing "git@ URLs"
    (is (git/git-url? "git@github.com:ring-clojure/ring.git")))
  (testing "local paths are not URLs"
    (is (not (git/git-url? "/path/to/repo")))
    (is (not (git/git-url? "../ring")))
    (is (not (git/git-url? "ring")))
    (is (not (git/git-url? ".")))))

(deftest url->repo-name-extracts-name
  (is (= "ring" (git/url->repo-name "https://github.com/ring-clojure/ring.git")))
  (is (= "ring" (git/url->repo-name "https://github.com/ring-clojure/ring")))
  (is (= "repo" (git/url->repo-name "git@github.com:user/repo.git")))
  (is (= "bar"  (git/url->repo-name "https://example.com/foo/bar/"))))

(deftest error-non-git-directory
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"git log failed"
                        (git/git-log (System/getProperty "java.io.tmpdir")))))

(deftest git-log-rejects-oversized-output
  (testing "throws when output exceeds max-git-output-bytes"
    (with-redefs [shell/sh (fn [& _args]
                             {:exit 0 :out (apply str (repeat 100000001 "x")) :err ""})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds.*bytes"
                            (git/git-log "/fake/repo"))))))

(deftest error-non-existent-path
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"git log failed"
                        (git/git-log "/nonexistent/path/12345"))))
