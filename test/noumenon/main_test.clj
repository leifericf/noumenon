(ns noumenon.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [noumenon.main :as main]))

;; --- Tier 0: Pure function tests ---

(deftest derive-db-name-simple
  (is (= "ring" (main/derive-db-name "/path/to/ring"))))

(deftest derive-db-name-trailing-slash
  (is (= "ring" (main/derive-db-name "/path/to/ring/"))))

(deftest derive-db-name-multiple-trailing-slashes
  (is (= "ring" (main/derive-db-name "/path/to/ring///"))))

(deftest derive-db-name-single-component
  (is (= "ring" (main/derive-db-name "ring"))))

(deftest resolve-db-dir-default
  (let [result (main/resolve-db-dir {})]
    (is (str/ends-with? result "data/datomic"))
    (is (not (str/starts-with? result ".")))))

(deftest resolve-db-dir-override
  (is (= "/tmp/mydb" (main/resolve-db-dir {:db-dir "/tmp/mydb"}))))

;; --- Tier 0: CLI dispatch / error cases (capture stdout+stderr) ---

(defn- run-capturing
  "Run main/run, capturing stdout and stderr strings."
  [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (let [result (main/run args)]
        (assoc result
               :stdout (str out)
               :stderr (str err))))))

(deftest no-args-shows-usage
  (let [{:keys [exit stdout stderr]} (run-capturing [])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Usage"))))

(deftest unknown-subcommand-shows-error
  (let [{:keys [exit stdout stderr]} (run-capturing ["frobnicate"])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Unknown subcommand: frobnicate"))
    (is (str/includes? stderr "Usage"))))

(deftest missing-repo-path
  (let [{:keys [exit stdout stderr]} (run-capturing ["import"])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Missing"))))

(deftest unknown-flag
  (let [{:keys [exit stdout stderr]} (run-capturing ["import" "--verbose" "/tmp"])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Unknown option: --verbose"))))

(deftest missing-db-dir-value
  (let [{:keys [exit stdout stderr]} (run-capturing ["import" "--db-dir"])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Missing value for --db-dir"))))

(deftest nonexistent-path
  (let [{:keys [exit stdout stderr]} (run-capturing ["import" "/nonexistent/path/12345"])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Path does not exist"))))

(deftest non-git-directory
  (let [{:keys [exit stdout stderr]} (run-capturing ["import" (System/getProperty "java.io.tmpdir")])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "not a git repository"))))

(deftest status-no-database
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-test-" (random-uuid))
        {:keys [exit stdout stderr]} (run-capturing ["status" "--db-dir" tmp-dir
                                                     (System/getProperty "user.dir")])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "No database found"))
    (is (str/includes? stderr "import"))))

;; --- Tier 1: Integration tests (in-memory Datomic is not feasible for CLI
;;     since main dispatches to connect-and-ensure-schema with a dir path,
;;     so we use a temp directory and the current repo) ---

(def ^:private repo-path (System/getProperty "user.dir"))

(deftest import-and-status-integration
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-cli-test-" (random-uuid))
        import1 (run-capturing ["import" "--db-dir" tmp-dir repo-path])
        import2 (run-capturing ["import" "--db-dir" tmp-dir repo-path])
        status  (run-capturing ["status" "--db-dir" tmp-dir repo-path])]
    (testing "first import succeeds"
      (is (= 0 (:exit import1)))
      (is (str/includes? (:stdout import1) ":commits-imported"))
      (is (str/includes? (:stdout import1) ":files-imported"))
      (is (str/includes? (:stdout import1) ":db-path")))
    (testing "second import is idempotent"
      (is (= 0 (:exit import2)))
      (is (str/includes? (:stdout import2) ":commits-imported 0"))
      (is (str/includes? (:stdout import2) ":files-imported 0")))
    (testing "status shows counts"
      (is (= 0 (:exit status)))
      (is (str/includes? (:stdout status) ":commits"))
      (is (str/includes? (:stdout status) ":files"))
      (is (str/includes? (:stdout status) ":dirs"))
      (is (str/includes? (:stdout status) ":db-path")))))

(deftest import-with-db-dir-flag
  (let [tmp-dir (str (.getAbsolutePath (java.io.File. (System/getProperty "java.io.tmpdir")))
                     "/noumenon-dbdir-test-" (random-uuid))
        result  (run-capturing ["import" "--db-dir" tmp-dir repo-path])]
    (is (= 0 (:exit result)))
    (is (str/includes? (:stdout result) tmp-dir))))
