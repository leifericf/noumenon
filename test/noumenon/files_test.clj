(ns noumenon.files-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.files :as files]
            [noumenon.test-helpers :as th]))

;; --- Tier 0: Pure function tests ---

(deftest parse-ls-tree-test
  (let [output (str "100644 blob abc1234    1234\tsrc/core.clj\n"
                    "100644 blob def5678    5678\tREADME.md\n"
                    "100755 blob ghi9012     890\tbin/run.sh\n")
        parsed (files/parse-ls-tree output)]
    (testing "parses correct count"
      (is (= 3 (count parsed))))
    (testing "parses fields correctly"
      (let [f (first parsed)]
        (is (= "100644" (:mode f)))
        (is (= "blob" (:type f)))
        (is (= "abc1234" (:sha f)))
        (is (= 1234 (:size f)))
        (is (= "src/core.clj" (:path f)))))
    (testing "handles executable mode"
      (is (= "100755" (:mode (nth parsed 2)))))))

(deftest parse-ls-tree-empty
  (is (nil? (files/parse-ls-tree "")))
  (is (nil? (files/parse-ls-tree nil)))
  (is (nil? (files/parse-ls-tree "   "))))

(deftest parse-ls-tree-dash-size
  (let [output "160000 commit abc1234       -\tvendor/lib\n"
        parsed (files/parse-ls-tree output)]
    (testing "submodule with dash size"
      (is (nil? (:size (first parsed)))))))

(deftest ext->lang-test
  (testing "known extensions"
    (is (= :clojure (files/ext->lang "clj")))
    (is (= :python (files/ext->lang "py")))
    (is (= :javascript (files/ext->lang "js")))
    (is (= :rust (files/ext->lang "rs"))))
  (testing "unknown extension"
    (is (nil? (files/ext->lang "xyz")))))

(deftest paths->dirs-test
  (testing "extracts all directories with intermediates"
    (is (= ["." "src" "src/noumenon"]
           (files/paths->dirs ["src/noumenon/core.clj" "src/noumenon/db.clj"]))))
  (testing "root-level files produce only root dir"
    (is (= ["."]
           (files/paths->dirs ["README.md" "deps.edn"]))))
  (testing "deeply nested paths"
    (is (= ["." "a" "a/b" "a/b/c"]
           (files/paths->dirs ["a/b/c/d.txt"]))))
  (testing "deduplicates shared prefixes"
    (is (= ["." "src" "src/a" "src/b"]
           (files/paths->dirs ["src/a/x.clj" "src/b/y.clj"]))))
  (testing "empty input"
    (is (= ["."]
           (files/paths->dirs [])))))

(deftest file->tx-data-test
  (let [file-map    {:mode "100644" :type "blob" :sha "abc" :size 1234 :path "src/core.clj"}
        line-counts {"src/core.clj" 42}
        tx          (files/file->tx-data file-map line-counts)]
    (testing "has required attributes"
      (is (= "src/core.clj" (:file/path tx)))
      (is (= "clj" (:file/ext tx)))
      (is (= :clojure (:file/lang tx)))
      (is (= 1234 (:file/size tx)))
      (is (= 42 (:file/lines tx)))
      (is (= "dir-src" (:file/directory tx))))))

(deftest file->tx-data-no-extension
  (let [tx (files/file->tx-data {:path "Makefile" :size 500} {})]
    (testing "no extension, no lang"
      (is (nil? (:file/ext tx)))
      (is (nil? (:file/lang tx))))
    (testing "directory is root"
      (is (= "dir-." (:file/directory tx))))))

(deftest file->tx-data-no-lines
  (let [tx (files/file->tx-data {:path "img/logo.png" :size 9999} {})]
    (testing "binary file has no line count"
      (is (nil? (:file/lines tx))))
    (testing "has extension but no lang"
      (is (= "png" (:file/ext tx)))
      (is (nil? (:file/lang tx))))))

(deftest file->tx-data-dotfile
  (let [tx (files/file->tx-data {:path ".gitignore" :size 100} {})]
    (testing "dotfile has no extension (leading dot is not an extension)"
      (is (nil? (:file/ext tx))))))

(deftest dir->tx-data-test
  (testing "root directory has no parent"
    (let [tx (files/dir->tx-data ".")]
      (is (= "." (:dir/path tx)))
      (is (= "dir-." (:db/id tx)))
      (is (nil? (:dir/parent tx)))))
  (testing "nested directory has parent"
    (let [tx (files/dir->tx-data "src/noumenon")]
      (is (= "src/noumenon" (:dir/path tx)))
      (is (= "dir-src" (:dir/parent tx)))))
  (testing "top-level directory has root parent"
    (let [tx (files/dir->tx-data "src")]
      (is (= "dir-." (:dir/parent tx))))))

(deftest sensitive-path-test
  (testing "env files are sensitive"
    (is (files/sensitive-path? ".env"))
    (is (files/sensitive-path? ".env.local"))
    (is (files/sensitive-path? ".env.production"))
    (is (files/sensitive-path? "config/.env"))
    (is (files/sensitive-path? "config/.env.staging")))
  (testing "env templates are safe"
    (is (not (files/sensitive-path? ".env.example")))
    (is (not (files/sensitive-path? ".env.sample")))
    (is (not (files/sensitive-path? ".env.template"))))
  (testing "crypto keys are sensitive"
    (is (files/sensitive-path? "certs/server.pem"))
    (is (files/sensitive-path? "ssl/private.key"))
    (is (files/sensitive-path? "keystore.p12"))
    (is (files/sensitive-path? "app.keystore"))
    (is (files/sensitive-path? "truststore.jks")))
  (testing "credential files are sensitive"
    (is (files/sensitive-path? ".npmrc"))
    (is (files/sensitive-path? ".pypirc"))
    (is (files/sensitive-path? ".netrc"))
    (is (files/sensitive-path? ".htpasswd"))
    (is (files/sensitive-path? ".pgpass"))
    (is (files/sensitive-path? "credentials.json"))
    (is (files/sensitive-path? "config/credentials.json")))
  (testing "SSH keys are sensitive"
    (is (files/sensitive-path? ".ssh/config"))
    (is (files/sensitive-path? ".ssh/authorized_keys"))
    (is (files/sensitive-path? "id_rsa"))
    (is (files/sensitive-path? "id_ed25519"))
    (is (files/sensitive-path? "id_ecdsa")))
  (testing "normal code and config files are safe"
    (is (not (files/sensitive-path? "src/core.clj")))
    (is (not (files/sensitive-path? "config.edn")))
    (is (not (files/sensitive-path? "package.json")))
    (is (not (files/sensitive-path? "README.md")))
    (is (not (files/sensitive-path? "deps.edn")))))

;; --- Tier 1: Integration tests (in-memory Datomic + real git repo) ---

(defn- test-conn []
  (th/make-test-conn "files-test"))

(def ^:private repo-path (System/getProperty "user.dir"))

(defn- ls-tree-file-count
  "Get the file count from git ls-tree for the repo."
  [repo-path]
  (-> (shell/sh "git" "-C" repo-path "ls-tree" "-r" "--name-only" "HEAD")
      :out str/split-lines count))

(deftest import-noumenon-files
  (let [expected (ls-tree-file-count repo-path)
        conn     (test-conn)
        result   (files/import-files! conn repo-path repo-path)
        db       (d/db conn)]
    (testing "imports correct number of files"
      (is (= expected (:files-imported result)))
      (is (= 0 (:files-skipped result))))
    (testing "files are queryable"
      (is (= expected
             (ffirst (d/q '[:find (count ?e) :where [?e :file/path _] [?e :file/size _]] db)))))
    (testing "directories were created"
      (is (pos? (:dirs-imported result)))
      (is (pos? (ffirst (d/q '[:find (count ?e) :where [?e :dir/path _]] db)))))
    (testing "known file has expected attributes"
      (let [entity (d/pull db '[:file/path :file/ext :file/lang :file/size :file/lines
                                {:file/directory [:dir/path]}]
                           [:file/path "src/noumenon/git.clj"])]
        (is (= "clj" (:file/ext entity)))
        (is (= :clojure (:file/lang entity)))
        (is (pos? (:file/size entity)))
        (is (pos? (:file/lines entity)))
        (is (= "src/noumenon" (get-in entity [:file/directory :dir/path])))))))

(deftest import-idempotency
  (let [conn    (test-conn)
        _       (files/import-files! conn repo-path repo-path)
        count1  (ffirst (d/q '[:find (count ?e) :where [?e :file/path _] [?e :file/size _]] (d/db conn)))
        dir-c1  (ffirst (d/q '[:find (count ?e) :where [?e :dir/path _]] (d/db conn)))
        result2 (files/import-files! conn repo-path repo-path)
        count2  (ffirst (d/q '[:find (count ?e) :where [?e :file/path _] [?e :file/size _]] (d/db conn)))
        dir-c2  (ffirst (d/q '[:find (count ?e) :where [?e :dir/path _]] (d/db conn)))]
    (testing "second import skips all files"
      (is (= 0 (:files-imported result2)))
      (is (= count1 (:files-skipped result2))))
    (testing "file count unchanged"
      (is (= count1 count2)))
    (testing "directory count unchanged"
      (is (= dir-c1 dir-c2)))))

(deftest import-enriches-stubs
  (let [conn (test-conn)]
    ;; Simulate git history import creating a file stub
    (d/transact conn {:tx-data [{:file/path "src/noumenon/git.clj"}]})
    (testing "stub exists without size"
      (let [before (d/pull (d/db conn) '[:file/path :file/size] [:file/path "src/noumenon/git.clj"])]
        (is (nil? (:file/size before)))))
    ;; Run file structure import
    (files/import-files! conn repo-path repo-path)
    (testing "stub is enriched with size and other attributes"
      (let [after (d/pull (d/db conn) '[:file/path :file/ext :file/size :file/lang]
                          [:file/path "src/noumenon/git.clj"])]
        (is (= "clj" (:file/ext after)))
        (is (pos? (:file/size after)))
        (is (= :clojure (:file/lang after)))))
    (testing "no duplicate entities"
      (is (= 1 (ffirst (d/q '[:find (count ?e) :where [?e :file/path "src/noumenon/git.clj"]]
                            (d/db conn))))))))

(deftest error-non-git-directory
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"git ls-tree failed"
                        (files/git-ls-tree (System/getProperty "java.io.tmpdir")))))

(deftest git-ls-tree-rejects-oversized-output
  (testing "throws when output exceeds max-git-output-bytes"
    (with-redefs [shell/sh (fn [& _args]
                             {:exit 0 :out (apply str (repeat 100000001 "x")) :err ""})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds.*bytes"
                            (files/git-ls-tree "/fake/repo"))))))
