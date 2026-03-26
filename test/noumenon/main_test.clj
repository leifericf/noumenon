(ns noumenon.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [noumenon.main :as main]
            [noumenon.util :as util]))

;; --- Tier 0: Pure function tests ---

(deftest derive-db-name-simple
  (is (= "ring" (util/derive-db-name "/path/to/ring"))))

(deftest derive-db-name-trailing-slash
  (is (= "ring" (util/derive-db-name "/path/to/ring/"))))

(deftest derive-db-name-multiple-trailing-slashes
  (is (= "ring" (util/derive-db-name "/path/to/ring///"))))

(deftest derive-db-name-single-component
  (is (= "ring" (util/derive-db-name "ring"))))

(deftest derive-db-name-sanitizes-special-chars
  (is (= "my-repo" (util/derive-db-name "/path/to/my-repo")))
  (is (= "my_repo" (util/derive-db-name "/path/to/my_repo")))
  (is (= "myrepo" (util/derive-db-name "/path/to/my repo")))
  (is (= "myrepo" (util/derive-db-name "/path/to/my$repo"))))

(deftest derive-db-name-resolves-dotdot
  (is (= "path" (util/derive-db-name "/path/to/..")))
  (is (some? (util/derive-db-name ".."))))

(deftest derive-db-name-rejects-empty-after-sanitize
  (is (nil? (util/derive-db-name "/path/to/$$$"))))

(deftest resolve-db-dir-default
  (let [result (util/resolve-db-dir {})]
    (is (str/ends-with? result "data/datomic"))
    (is (not (str/starts-with? result ".")))))

(deftest resolve-db-dir-override
  (is (= "/tmp/mydb" (util/resolve-db-dir {:db-dir "/tmp/mydb"}))))

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
    (is (str/includes? stderr "--help"))
    (is (str/includes? stderr "Usage"))))

(deftest missing-repo-path
  (let [{:keys [exit stdout stderr]} (run-capturing ["import"])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Missing"))))

(deftest unknown-flag
  (let [{:keys [exit stdout stderr]} (run-capturing ["import" "--frobnicate" "/tmp"])]
    (is (= 1 exit))
    (is (str/blank? stdout))
    (is (str/includes? stderr "Unknown option: --frobnicate"))))

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
    (testing "status shows human-readable summary on stderr"
      (is (= 0 (:exit status)))
      (is (str/includes? (:stderr status) "commits"))
      (is (str/includes? (:stderr status) "files"))
      (is (str/includes? (:stderr status) "directories"))
      (is (str/includes? (:stderr status) "db:")))))

(deftest import-with-db-dir-flag
  (let [tmp-dir (str (.getAbsolutePath (java.io.File. (System/getProperty "java.io.tmpdir")))
                     "/noumenon-dbdir-test-" (random-uuid))
        result  (run-capturing ["import" "--db-dir" tmp-dir repo-path])]
    (is (= 0 (:exit result)))
    (is (str/includes? (:stdout result) tmp-dir))))

;; --- Tier 0: Benchmark CLI arg parsing ---

(deftest benchmark-missing-repo-path
  (let [{:keys [exit stderr]} (run-capturing ["benchmark"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing"))))

(deftest benchmark-unknown-flag
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--frobnicate" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Unknown option: --frobnicate"))))

(deftest benchmark-verbose-rejected
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--verbose" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Unknown option: --verbose"))))

(deftest benchmark-resume-defaults-to-latest
  ;; --resume without value defaults to "latest"
  ;; Use a temp checkpoint dir to avoid stale files from real runs
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-resume-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--db-dir" tmp-dir "." "--resume"])]
    (is (= 1 exit))
    (is (or (str/includes? stderr "No checkpoint files found")
            (str/includes? stderr "No database found")))))

(deftest benchmark-resume-specific-run-id
  (let [fake-id "1234-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude" "--resume" fake-id "."])]
    (is (= 1 exit))
    (is (or (str/includes? stderr (str "Checkpoint not found: " fake-id))
            (str/includes? stderr "No database found")))))

(deftest benchmark-invalid-max-questions
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--max-questions" "abc" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --max-questions"))))

(deftest benchmark-missing-max-questions-value
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--max-questions"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing value for --max-questions"))))

(deftest benchmark-invalid-stop-after
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--stop-after" "xyz" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --stop-after"))))

(deftest benchmark-nonexistent-repo
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "/nonexistent/path"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Path does not exist"))))

(deftest benchmark-no-database
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-bench-test-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

;; --- Tier 0: Model/provider CLI flags ---

(deftest benchmark-model-flag-parsed
  ;; --model with no database is fine — we just check that parsing succeeds and
  ;; we get to the "no database" error rather than an unknown-flag error
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-model-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--model" "haiku" "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

(deftest benchmark-judge-model-flag-parsed
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-jm-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--judge-model" "haiku"
                                              "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

(deftest benchmark-provider-flag-parsed
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-prov-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

(deftest benchmark-provider-aliases-are-accepted
  (doseq [provider ["claude" "claude-cli" "claude-api" "glm"]]
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                       "/noumenon-prov-alias-" provider "-" (random-uuid))
          {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" provider
                                                "--db-dir" tmp-dir repo-path])]
      (is (= 1 exit))
      (is (or (str/includes? stderr "No database found")
              (str/includes? stderr "NOUMENON_ZAI_TOKEN")
              (str/includes? stderr "ANTHROPIC_API_KEY"))))))

(deftest benchmark-invalid-provider
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "openai" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --provider"))))

(deftest benchmark-missing-model-value
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--model"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing value for --model"))))

(deftest benchmark-glm-without-token
  ;; GLM provider without NOUMENON_ZAI_TOKEN should fail fast
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-glm-" (random-uuid))]
    ;; Import first so we have a database
    (run-capturing ["import" "--db-dir" tmp-dir repo-path])
    (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "glm"
                                                "--db-dir" tmp-dir repo-path])]
      (is (= 1 exit))
      (is (str/includes? stderr "NOUMENON_ZAI_TOKEN")))))

(deftest benchmark-max-cost-flag-parsed
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-maxcost-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--max-cost" "5.00"
                                              "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

(deftest benchmark-invalid-max-cost
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--max-cost" "abc" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --max-cost"))))

;; --- Tier 0: Concurrency CLI flags ---

(deftest benchmark-concurrency-flag-parsed
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-conc-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--concurrency" "4"
                                              "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

(deftest benchmark-concurrency-invalid-zero
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--concurrency" "0" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --concurrency"))))

(deftest benchmark-concurrency-invalid-too-high
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--concurrency" "21" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --concurrency"))))

(deftest benchmark-concurrency-invalid-non-numeric
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--concurrency" "abc" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --concurrency"))))

(deftest benchmark-missing-concurrency-value
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--concurrency"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing value for --concurrency"))))

(deftest benchmark-min-delay-flag-parsed
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-delay-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--min-delay" "200"
                                              "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

(deftest benchmark-min-delay-zero-valid
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-delay0-" (random-uuid))
        {:keys [exit stderr]} (run-capturing ["benchmark" "--provider" "claude"
                                              "--min-delay" "0"
                                              "--db-dir" tmp-dir repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "No database found"))))

(deftest benchmark-invalid-min-delay
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--min-delay" "abc" "."])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --min-delay"))))

(deftest benchmark-missing-min-delay-value
  (let [{:keys [exit stderr]} (run-capturing ["benchmark" "--min-delay"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing value for --min-delay"))))

;; --- Help and version ---

(deftest global-help
  (let [{:keys [exit stdout stderr]} (run-capturing ["--help"])]
    (is (= 0 exit))
    (is (str/includes? stdout "Usage"))
    (is (str/includes? stdout "import"))
    (is (str/includes? stdout "benchmark"))
    (is (str/blank? stderr))))

(deftest global-help-short
  (let [{:keys [exit stdout]} (run-capturing ["-h"])]
    (is (= 0 exit))
    (is (str/includes? stdout "Usage"))))

(deftest subcommand-help
  (let [{:keys [exit stdout stderr]} (run-capturing ["benchmark" "--help"])]
    (is (= 0 exit))
    (is (str/includes? stdout "benchmark"))
    (is (str/includes? stdout "--skip-raw"))
    (is (str/blank? stderr))))

(deftest ask-help
  (let [{:keys [exit stdout]} (run-capturing ["ask" "--help"])]
    (is (= 0 exit))
    (is (str/includes? stdout "--question"))
    (is (str/includes? stdout "Exit codes"))))

(deftest version-flag
  (let [{:keys [exit stdout stderr]} (run-capturing ["--version"])]
    (is (= 0 exit))
    (is (not (str/blank? stdout)))
    (is (str/blank? stderr))))

;; --- Ask subcommand ---

(deftest ask-missing-question
  (let [{:keys [exit stderr]} (run-capturing ["ask" repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing -q"))))

(deftest ask-missing-repo-path
  (let [{:keys [exit stderr]} (run-capturing ["ask" "-q" "some question"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing"))))

(deftest ask-invalid-max-iterations
  (let [{:keys [exit stderr]} (run-capturing ["ask" "-q" "q" "--max-iterations" "abc" "/tmp"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --max-iterations"))))

(deftest ask-missing-max-iterations-value
  (let [{:keys [exit stderr]} (run-capturing ["ask" "-q" "q" "--max-iterations"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing value for --max-iterations"))))

(deftest ask-unknown-flag
  (let [{:keys [exit stderr]} (run-capturing ["ask" "-q" "q" "--unknown" "/tmp"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Unknown option"))))

(deftest ask-no-database
  (let [{:keys [exit stderr]} (run-capturing ["ask" "-q" "question" "/tmp/nonexistent-repo-xyz"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Path does not exist"))))

;; --- Tier 0: Query --param flag ---

(deftest query-help-shows-list-subcommand
  (let [{:keys [exit stdout]} (run-capturing ["query" "--help"])]
    (is (= 0 exit))
    (is (str/includes? stdout "query list")
        "Help text should mention 'query list' sub-subcommand")))

(deftest query-param-flag-parsed
  (let [{:keys [exit stderr]} (run-capturing ["query" "--param" "file-path=src/main.clj"
                                              "files-by-complexity" repo-path])]
    (is (or (= 0 exit) (str/includes? stderr "No database found"))
        "Should parse --param without error")))

(deftest query-param-missing-value
  (let [{:keys [exit stderr]} (run-capturing ["query" "--param"])]
    (is (= 1 exit))
    (is (str/includes? stderr "Missing value for --param"))))

(deftest query-param-invalid-format
  (let [{:keys [exit stderr]} (run-capturing ["query" "--param" "noequals"
                                              "files-by-complexity" repo-path])]
    (is (= 1 exit))
    (is (str/includes? stderr "Invalid --param"))))

;; --- Tier 1: Query subcommand integration ---

(deftest query-files-by-complexity
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/noumenon-query-test-" (random-uuid))
        _       (run-capturing ["import" "--db-dir" tmp-dir repo-path])
        {:keys [exit stdout stderr]} (run-capturing ["query" "files-by-complexity"
                                                     "--db-dir" tmp-dir repo-path])]
    (testing "query exits successfully"
      (is (= 0 exit) (str "Expected exit 0, stderr: " stderr)))
    (testing "stdout contains query results"
      (is (not (str/blank? stdout))))))
