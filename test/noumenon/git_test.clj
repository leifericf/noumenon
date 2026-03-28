(ns noumenon.git-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.git :as git]
            [noumenon.test-helpers :as th]
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

(deftest parse-malformed-record-skipped
  (testing "records with too few fields are silently dropped"
    (let [malformed "\u0001only\u0000two-fields"
          commits   (git/parse-commits malformed)]
      (is (= [] commits))))
  (testing "malformed records mixed with valid ones are filtered out"
    (let [valid     (make-record "aaa" "" "A" "a@x.com" iso-ts
                                 "A" "a@x.com" iso-ts "good" ["f.txt"])
          malformed "\u0001bad\u0000record"
          log       (str valid malformed)
          commits   (git/parse-commits log)]
      (is (= 1 (count commits)))
      (is (= "aaa" (:sha (first commits)))))))

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

;; --- Issue reference extraction ---

(deftest extract-issue-refs-github-style
  (is (= #{"#123" "#456"}
         (git/extract-issue-refs "fix(auth): resolve login bug (#123) and (#456)"))))

(deftest extract-issue-refs-jira-style
  (is (= #{"PROJ-42" "PROJ-999"}
         (git/extract-issue-refs "PROJ-42 implement feature, see also PROJ-999"))))

(deftest extract-issue-refs-urls
  (is (= #{"https://github.com/foo/bar/issues/123"}
         (git/extract-issue-refs "Closes https://github.com/foo/bar/issues/123"))))

(deftest extract-issue-refs-mixed
  (let [refs (git/extract-issue-refs "Fix #55 (JIRA-100) see https://jira.example.com/browse/JIRA-100")]
    (is (contains? refs "#55"))
    (is (contains? refs "JIRA-100"))
    (is (some #(str/starts-with? % "https://") refs))))

(deftest extract-issue-refs-none
  (is (nil? (git/extract-issue-refs "chore: bump deps")))
  (is (nil? (git/extract-issue-refs nil)))
  (is (nil? (git/extract-issue-refs ""))))

(deftest tx-data-includes-issue-refs
  (let [commit  {:sha "abc" :parent-shas [] :message "fix(api): handle timeout (#42, PROJ-7)"
                 :author-name "A" :author-email "a@x.com" :authored-at #inst "2024-01-01"
                 :committer-name "A" :committer-email "a@x.com" :committed-at #inst "2024-01-01"
                 :changed-files []}
        tx-data (git/commit->tx-data "test://repo" commit)
        entity  (first (filter :git/sha tx-data))]
    (is (= #{"#42" "PROJ-7"} (:commit/issue-refs entity)))))

(deftest tx-data-omits-issue-refs-when-none
  (let [commit  {:sha "abc" :parent-shas [] :message "chore: update deps"
                 :author-name "A" :author-email "a@x.com" :authored-at #inst "2024-01-01"
                 :committer-name "A" :committer-email "a@x.com" :committed-at #inst "2024-01-01"
                 :changed-files []}
        tx-data (git/commit->tx-data "test://repo" commit)
        entity  (first (filter :git/sha tx-data))]
    (is (nil? (:commit/issue-refs entity)))))

;; --- Rename path resolution (regression: Flask import crash) ---

(deftest resolve-rename-path-directory
  (testing "directory rename {old => new}/file resolves to destination"
    (is (= "flask/testsuite/foo.py"
           (#'git/resolve-rename-path "{tests => flask/testsuite}/foo.py")))))

(deftest resolve-rename-path-file
  (testing "file rename src/{old.py => new.py} resolves to destination"
    (is (= "src/new.py"
           (#'git/resolve-rename-path "src/{old.py => new.py}")))))

(deftest resolve-rename-path-passthrough
  (testing "non-rename paths pass through unchanged"
    (is (= "src/core.clj" (#'git/resolve-rename-path "src/core.clj")))))

(deftest resolve-rename-path-double-slash
  (testing "double slashes from empty rename segments are normalized"
    (is (= "templates/mail.txt"
           (#'git/resolve-rename-path "{tests => }/templates/mail.txt")))))

(deftest parse-numstat-with-rename
  (testing "numstat line with rename syntax resolves to destination"
    (let [log     (str "\u0001"
                       "sha1" "\u0000"
                       "" "\u0000"
                       "A" "\u0000"
                       "a@x.com" "\u0000"
                       iso-ts "\u0000"
                       "A" "\u0000"
                       "a@x.com" "\u0000"
                       iso-ts "\u0000"
                       "rename commit\n\u0000"
                       "\n3\t1\t{tests => flask/testsuite}/helpers.py\n")
          commits (git/parse-commits log)
          c       (first commits)]
      (is (= ["flask/testsuite/helpers.py"] (:changed-files c)))
      (is (= 3 (:additions c))))))

(deftest parse-numstat-binary-file
  (testing "binary files with - for additions/deletions parse as zero"
    (let [log     (str "\u0001"
                       "sha2" "\u0000"
                       "" "\u0000"
                       "A" "\u0000"
                       "a@x.com" "\u0000"
                       iso-ts "\u0000"
                       "A" "\u0000"
                       "a@x.com" "\u0000"
                       iso-ts "\u0000"
                       "add image\n\u0000"
                       "\n-\t-\tlogo.png\n")
          commits (git/parse-commits log)
          c       (first commits)]
      (is (= ["logo.png"] (:changed-files c)))
      (is (= 0 (:additions c)))
      (is (= 0 (:deletions c))))))

;; --- Author/committer shared-email dedup (regression: Flask commit bcba7eb) ---

(deftest same-email-produces-one-person-entity
  (testing "when author and committer share the same email, only one person entity is emitted"
    (let [commit  {:sha "bcba7eb" :parent-shas []
                   :author-name "flowerhack" :author-email "julia@flowerhack.com"
                   :authored-at #inst "2024-01-01"
                   :committer-name "Julia Hansbrough" :committer-email "julia@flowerhack.com"
                   :committed-at #inst "2024-01-01"
                   :message "fix tests" :changed-files []}
          tx-data (git/commit->tx-data "test-repo" commit)
          persons (filter :person/email tx-data)]
      (is (= 1 (count persons))
          "Same email should produce exactly one person entity")
      (is (= "flowerhack" (:person/name (first persons)))
          "Author name takes precedence when emails match"))))

(deftest different-email-produces-two-person-entities
  (testing "when author and committer have different emails, two person entities are emitted"
    (let [commit  {:sha "abc" :parent-shas []
                   :author-name "Alice" :author-email "alice@x.com"
                   :authored-at #inst "2024-01-01"
                   :committer-name "Bob" :committer-email "bob@x.com"
                   :committed-at #inst "2024-01-01"
                   :message "test" :changed-files []}
          tx-data (git/commit->tx-data "test-repo" commit)
          persons (filter :person/email tx-data)]
      (is (= 2 (count persons))))))

;; --- Tier 1: Integration tests (in-memory Datomic + real git repo) ---

(defn- test-conn []
  (th/make-test-conn "git-test"))

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

;; --- SSRF protection ---

(deftest clone-blocks-loopback-url
  (testing "clone! rejects URLs resolving to loopback"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Blocked.*private"
                          (git/clone! "https://localhost/evil/repo.git" "/tmp/test-clone")))))

(deftest clone-blocks-private-ip-url
  (testing "clone! rejects URLs with private IPs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Blocked.*private"
                          (git/clone! "https://192.168.1.1/evil/repo.git" "/tmp/test-clone")))))

(deftest clone-blocks-127-ip-url
  (testing "clone! rejects URLs with 127.x.x.x"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Blocked.*private"
                          (git/clone! "https://127.0.0.1/evil/repo.git" "/tmp/test-clone")))))

(deftest blocked-address-detects-ipv4-mapped-ipv6
  (testing "blocked-address? catches IPv4-mapped IPv6 loopback"
    (let [addr (java.net.InetAddress/getByName "::ffff:127.0.0.1")]
      (is (#'git/blocked-address? addr))))
  (testing "blocked-address? catches IPv4-mapped IPv6 private"
    (let [addr (java.net.InetAddress/getByName "::ffff:192.168.1.1")]
      (is (#'git/blocked-address? addr)))))
