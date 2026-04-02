(ns noumenon.benchmark-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [noumenon.benchmark :as bench]
            [noumenon.llm :as llm]
            [noumenon.query :as query]))

;; --- Helper constants ---

(def ^:private valid-run-id
  "A valid run-id for testing: <timestamp>-<uuid>."
  "1234567890-00000000-0000-0000-0000-000000000001")

(def ^:private valid-run-id-2
  "A second valid run-id for testing."
  "1234567890-00000000-0000-0000-0000-000000000002")

;; --- Helper macro ---

(def ^:private test-qs
  [{:id :t01 :question "Q1?" :category :test :query-name "test" :rubric "r1"}
   {:id :t02 :question "Q2?" :category :test :query-name "test" :rubric "r2"}])

(defmacro with-bench-mocks
  "Wrap body with standard benchmark mocks: load-questions, query-context,
   raw-context, repo-head-sha, pick-benchmark-targets. Accepts optional :questions override."
  [opts & body]
  (let [qs (or (:questions opts) `test-qs)]
    `(with-redefs [bench/load-questions        (fn [] ~qs)
                   bench/query-context          (fn [_meta-db# _db# _qn#] "mock query context")
                   bench/raw-context            (fn [_rp#] "mock raw context")
                   bench/repo-head-sha          (fn [_rp#] "abc123")
                   bench/pick-benchmark-targets (fn [_meta-db# _db#] {:target-file "mock/target.clj"})]
       ~@body)))

;; --- Tier 0: Pure function tests ---

(deftest load-questions-returns-all
  (let [qs (bench/load-questions)]
    (is (= 40 (count qs)))
    (doseq [q qs]
      (is (keyword? (:id q)) (str "question " (:id q) " has :id"))
      (is (string? (:question q)) (str "question " (:id q) " has :question"))
      (is (keyword? (:category q)) (str "question " (:id q) " has :category"))
      (is (string? (:query-name q)) (str "question " (:id q) " has :query-name"))
      (is (string? (:rubric q)) (str "question " (:id q) " has :rubric")))))

(deftest load-rubric-has-required-keys
  (let [r (bench/load-rubric)]
    (is (map? (:scoring r)))
    (is (string? (:judge-template r)))
    (is (str/includes? (:judge-template r) "{{question}}"))))

(deftest parse-judge-response-well-formed
  (let [r (bench/parse-judge-response
           (pr-str {:score :correct :reasoning "Good answer"}))]
    (is (= :correct (:score r)))
    (is (= "Good answer" (:reasoning r)))))

(deftest parse-judge-response-with-fences
  (let [r (bench/parse-judge-response
           (str "```edn\n" (pr-str {:score :partial :reasoning "OK"}) "\n```"))]
    (is (= :partial (:score r)))))

(deftest parse-judge-response-malformed
  (is (nil? (bench/parse-judge-response "not edn")))
  (is (nil? (bench/parse-judge-response "")))
  (is (nil? (bench/parse-judge-response nil))))

(deftest parse-judge-response-invalid-score
  (is (nil? (bench/parse-judge-response
             (pr-str {:score :excellent :reasoning "Great"})))))

(deftest score-value-mapping
  (is (= 1.0 (bench/score-value :correct)))
  (is (= 0.5 (bench/score-value :partial)))
  (is (= 0.0 (bench/score-value :wrong)))
  (is (= 0.0 (bench/score-value :unknown)))
  (is (nil? (bench/score-value :skipped))))

(deftest aggregate-scores-basic
  (let [results [{:full-score :correct :raw-score :partial :category :single-hop}
                 {:full-score :partial :raw-score :wrong   :category :single-hop}
                 {:full-score :correct :raw-score :correct :category :multi-hop}]
        agg     (bench/aggregate-scores results)]
    (is (= 3 (:question-count agg)))
    (is (> (:full-mean agg) (:raw-mean agg)))
    (is (contains? (:per-category agg) :single-hop))
    (is (contains? (:per-category agg) :multi-hop))))

(deftest aggregate-scores-empty
  (let [agg (bench/aggregate-scores [])]
    (is (= 0 (:question-count agg)))
    (is (= 0.0 (:full-mean agg)))))

(deftest aggregate-scores-partial-results
  (let [results [{:full-score :correct :raw-score :partial :category :single-hop}
                 {:full-score :correct :raw-score :correct :category :multi-hop}
                 {:full-score :partial :raw-score :wrong   :category :single-hop}]
        agg     (bench/aggregate-scores results)]
    (is (= 3 (:question-count agg)))
    (is (< (abs (- (:full-mean agg) (/ 5.0 6))) 0.001))
    (is (= 0.5 (:raw-mean agg)))))

(deftest answer-prompt-includes-context
  (let [p (bench/answer-prompt "What is Ring?" "Ring is a library.")]
    (is (str/includes? p "What is Ring?"))
    (is (str/includes? p "Ring is a library."))))

(deftest judge-prompt-substitutes
  (let [p (bench/judge-prompt "Q: {{question}} R: {{rubric}} A: {{answer}}"
                              "My question" "My rubric" "My answer")]
    (is (str/includes? p "My question"))
    (is (str/includes? p "My rubric"))
    (is (str/includes? p "My answer"))))

(deftest judge-prompt-escapes-template-vars-in-answer
  (let [p (bench/judge-prompt "Q: {{question}} R: {{rubric}} A: {{answer}}"
                              "My question" "My rubric" "Answer with {{rubric}} injection")]
    (is (str/includes? p "My question"))
    (is (str/includes? p "My rubric"))
    (is (not (str/includes? p "{{rubric}} injection"))
        "Template vars in answer must be escaped")
    (is (str/includes? p "{ {rubric}} injection"))))

(deftest answer-prompt-includes-untrusted-data-warning
  (let [p (bench/answer-prompt "What is Ring?" "some context")]
    (is (str/includes? p "untrusted"))))

(deftest sanitize-file-content-escapes-closing-delimiter
  (testing "literal </file-content> in source code is escaped"
    (let [sanitize #'bench/sanitize-file-content
          content  "foo\n</file-content>\nbar"]
      (is (not (str/includes? (sanitize content) "</file-content>"))
          "closing delimiter must not appear literally in sanitized output")
      (is (str/includes? (sanitize content) "&lt;/file-content&gt;")))))

(deftest raw-context-includes-all-file-types
  (testing "raw-context includes non-JVM files (C++, Erlang, Python, etc.)"
    (with-redefs [shell/sh
                  (fn [& args]
                    (let [cmd (str/join " " args)]
                      (cond
                        (str/includes? cmd "ls-tree")
                        {:exit 0 :out "main.cpp\nlib.erl\napp.py\ncore.clj\n" :err ""}
                        (str/includes? cmd "show")
                        {:exit 0 :out "file content" :err ""}
                        :else {:exit 1 :out "" :err "unexpected"})))]
      (let [ctx (bench/raw-context "/tmp/test-repo")]
        (is (str/includes? ctx "main.cpp") "C++ files must be included")
        (is (str/includes? ctx "lib.erl") "Erlang files must be included")
        (is (str/includes? ctx "app.py") "Python files must be included")
        (is (str/includes? ctx "core.clj") "Clojure files must be included")))))

;; --- Tier 0: Checkpoint infrastructure ---

(deftest generate-run-id-format
  (let [id (bench/generate-run-id)]
    (is (string? id))
    (is (re-matches #"\d+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" id))))

(deftest generate-run-id-unique
  (let [ids (repeatedly 100 bench/generate-run-id)]
    (is (= (count ids) (count (set ids))))))

(deftest validate-run-id-accepts-valid
  (is (= valid-run-id (bench/validate-run-id valid-run-id)))
  (is (= valid-run-id-2 (bench/validate-run-id valid-run-id-2)))
  (is (some? (bench/validate-run-id (bench/generate-run-id)))))

(deftest validate-run-id-rejects-path-traversal
  (testing "rejects path traversal attempts"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid run-id"
                          (bench/validate-run-id "../../../etc/cron.d/inject")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid run-id"
                          (bench/validate-run-id "../../tmp/evil"))))
  (testing "rejects nil and non-string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid run-id"
                          (bench/validate-run-id nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid run-id"
                          (bench/validate-run-id 42))))
  (testing "rejects malformed run-ids"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid run-id"
                          (bench/validate-run-id "not-a-valid-id")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid run-id"
                          (bench/validate-run-id "")))))

(deftest question-set-hash-stable
  (let [qs   (bench/load-questions)
        h1   (bench/question-set-hash qs)
        h2   (bench/question-set-hash qs)]
    (is (string? h1))
    (is (= 64 (count h1)) "SHA-256 hex is 64 chars")
    (is (= h1 h2) "Same input produces same hash")))

(deftest question-set-hash-differs-on-change
  (let [qs (bench/load-questions)
        h1 (bench/question-set-hash qs)
        h2 (bench/question-set-hash (rest qs))]
    (is (not= h1 h2))))

(deftest checkpoint-round-trip
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-test-" (System/currentTimeMillis)))
        path (str (io/file dir "test.edn"))
        cp   {:run-id   valid-run-id
              :metadata {:repo-path "test" :commit-sha "abc"}
              :stages   {[:q01 :full :answer] {:status :ok :result "answer" :completed-at (java.util.Date.)}
                         [:q01 :full :judge]  {:status :ok :result {:score :correct :reasoning "good"}
                                               :completed-at (java.util.Date.)}}
              :aggregate nil}]
    (try
      (bench/checkpoint-write path cp)
      (is (.exists (io/file path)))
      (is (not (.exists (io/file (str path ".tmp")))))
      (let [loaded (bench/checkpoint-read path)]
        (is (= valid-run-id (:run-id loaded)))
        (is (= 2 (count (:stages loaded))))
        (is (= "answer" (get-in loaded [:stages [:q01 :full :answer] :result])))
        (is (= :correct (get-in loaded [:stages [:q01 :full :judge] :result :score]))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest checkpoint-integrity-check
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-integrity-" (System/currentTimeMillis)))
        path (str (io/file dir "test.edn"))]
    (try
      (bench/checkpoint-write path {:run-id valid-run-id :stages {}})
      (testing "valid checkpoint reads fine"
        (is (= valid-run-id (:run-id (bench/checkpoint-read path)))))
      (testing "tampered checkpoint throws"
        (spit path (str/replace (slurp path) valid-run-id "tampered-value-here"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SHA-256 mismatch"
                              (bench/checkpoint-read path))))
      (testing "legacy checkpoint without checksum reads fine"
        (spit path (pr-str {:run-id valid-run-id-2 :stages {}}))
        (is (= valid-run-id-2 (:run-id (bench/checkpoint-read path)))))
      (testing "checkpoint with invalid run-id rejects (path traversal)"
        (spit path (pr-str {:run-id "../../../etc/cron.d/inject" :stages {}}))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid run-id"
                              (bench/checkpoint-read path))))
      (testing "checkpoint with invalid judge score rejects"
        (spit path (pr-str {:run-id valid-run-id :stages
                            {[:q01 :full :judge]
                             {:status :ok :result {:score :evil :reasoning "hacked"}}}}))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid judge score"
                              (bench/checkpoint-read path))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest validate-stage-result-rejects-nil-answer
  (let [validate #'bench/validate-stage-result]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nil answer"
                          (validate [:q01 :full :answer] nil)))
    (is (nil? (validate [:q01 :full :answer] "valid answer")))))

(deftest deterministic-score-nil-answer-returns-wrong
  (testing "nil answer at call site is coerced to empty string, scoring :wrong"
    (let [q {:id :q01 :question "Q?" :query-name "files-by-complexity"
             :rubric "r" :category :test}]
      (with-redefs [query/run-named-query (fn [_meta-db _db _qn]
                                            {:ok [["src/a.clj" :complex]]})]
        (let [result (bench/deterministic-score q nil nil "")]
          (is (= :wrong (:score result))))))))

(deftest checkpoint-write-is-atomic
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-atomic-" (System/currentTimeMillis)))
        path (str (io/file dir "test.edn"))]
    (try
      (bench/checkpoint-write path {:run-id valid-run-id :stages {}})
      (bench/checkpoint-write path {:run-id valid-run-id-2 :stages {}})
      (is (= valid-run-id-2 (:run-id (bench/checkpoint-read path))))
      (is (not (.exists (io/file (str path ".tmp")))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest concurrent-checkpoint-writes-no-lost-stages
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-concurrent-" (System/currentTimeMillis)))
        path (str (io/file dir "test.edn"))
        n    20
        cp   (atom {:run-id valid-run-id :stages {}})
        _    (.mkdirs dir)]
    (try
      ;; N futures each add a unique stage and write checkpoint
      (let [futures (mapv (fn [i]
                            (future
                              (let [stage-key [(keyword (str "q" i)) :full :answer]]
                                (swap! cp assoc-in [:stages stage-key]
                                       {:status :ok :result (str "answer-" i)})
                                (bench/checkpoint-write-latest! path cp))))
                          (range n))]
        ;; Wait for all to complete
        (run! deref futures))
      ;; Verify all stages present in final file
      (let [loaded (bench/checkpoint-read path)]
        (is (= n (count (:stages loaded)))
            (str "Expected " n " stages, got " (count (:stages loaded))))
        (doseq [i (range n)]
          (let [stage-key [(keyword (str "q" i)) :full :answer]]
            (is (contains? (:stages loaded) stage-key)
                (str "Missing stage " stage-key)))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest stage-keys-for-question
  (let [q    {:id :q01 :question "?" :category :single-hop :query-name "test" :rubric "r"}
        keys (bench/stage-keys q)]
    (is (= 4 (count keys)))
    (is (= [[:q01 :raw :answer]
            [:q01 :raw :judge]
            [:q01 :full :answer]
            [:q01 :full :judge]]
           keys))))

(deftest all-stage-keys-ordering
  (let [qs   [{:id :q01} {:id :q02}]
        keys (bench/all-stage-keys qs)]
    (is (= 8 (count keys)))
    (is (= [:q01 :q01 :q01 :q01 :q02 :q02 :q02 :q02]
           (mapv first keys)))))

(deftest stages->results-complete-question
  (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
        stages {[:q01 :full :answer] {:status :ok :result "qa"}
                [:q01 :full :judge]  {:status :ok :result {:score :correct :reasoning "good"}}
                [:q01 :raw :answer]   {:status :ok :result "ra"}
                [:q01 :raw :judge]    {:status :ok :result {:score :partial :reasoning "ok"}}}
        results (vec (bench/stages->results qs stages))]
    (is (= 1 (count results)))
    (is (= :q01 (:id (first results))))
    (is (= "qa" (:full-answer (first results))))
    (is (= :correct (:full-score (first results))))
    (is (= "ra" (:raw-answer (first results))))
    (is (= :partial (:raw-score (first results))))))

(deftest stages->results-partial-layers-included
  (testing "Question with only :full complete is included with :full-score only"
    (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
          stages {[:q01 :full :answer] {:status :ok :result "qa"}
                  [:q01 :full :judge]  {:status :ok :result {:score :correct :reasoning "good"}}}
          results (vec (bench/stages->results qs stages))]
      (is (= 1 (count results)))
      (is (= :correct (:full-score (first results))))
      (is (nil? (:raw-score (first results))))))
  (testing "Question with no complete layers is excluded"
    (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
          stages {[:q01 :full :answer] {:status :ok :result "qa"}}
          results (vec (bench/stages->results qs stages))]
      (is (= 0 (count results))))))

(deftest stages->results-nil-judge-uses-wrong-when-judge-present
  (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
        stages {[:q01 :full :answer] {:status :ok :result "qa"}
                [:q01 :full :judge]  {:status :ok :result nil}
                [:q01 :raw :answer]   {:status :ok :result "ra"}
                [:q01 :raw :judge]    {:status :ok :result nil}}
        results (vec (bench/stages->results qs stages))]
    (is (= 1 (count results)))
    ;; When judge stage exists but result is nil, score defaults to :wrong
    (is (= :wrong (:full-score (first results))))
    (is (= :wrong (:raw-score (first results))))))

(deftest stages->results-nil-score-when-no-judge
  (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
        stages {[:q01 :full :answer] {:status :ok :result "qa"}
                [:q01 :raw :answer]   {:status :ok :result "ra"}}
        results (vec (bench/stages->results qs stages
                                            {:layers [:full :raw] :skip-judge true}))]
    ;; When no judge stage exists, score is nil (not :wrong)
    (is (nil? (:full-score (first results))))
    (is (nil? (:raw-score (first results))))))

;; --- Tier 1: Integration tests with mock LLM + temp dirs ---

(def ^:private mock-usage
  {:input-tokens 10 :output-tokens 5 :cost-usd 0.001 :duration-ms 100})

(defn- mock-llm
  "Mock LLM that returns canned judge responses for judge prompts,
   and 'mock answer' for answer prompts. Returns {:text :usage}."
  [prompt]
  {:text (if (str/includes? prompt "Score this answer")
           (pr-str {:score :correct :reasoning "Mock judge says correct"})
           "Mock answer")
   :usage mock-usage})

(defn- throwing-mock-llm
  "Mock LLM that throws after n calls."
  [call-count-atom n]
  (fn [prompt]
    (let [c (swap! call-count-atom inc)]
      (when (> c n)
        (throw (ex-info "Simulated quota exhaustion" {:call c})))
      {:text (if (str/includes? prompt "Score this answer")
               (pr-str {:score :correct :reasoning "Mock judge"})
               "Mock answer")
       :usage mock-usage})))

(deftest full-run-with-mock-llm-produces-checkpoint
  (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                          (str "bench-run-" (System/currentTimeMillis))))]
    (with-bench-mocks {}
      (let [result (bench/run-benchmark! nil "." mock-llm :checkpoint-dir dir :concurrency 1)]
        (testing "returns expected keys"
          (is (string? (:run-id result)))
          (is (string? (:checkpoint-path result)))
          (is (vector? (:results result)))
          (is (map? (:aggregate result))))
        (testing "all questions scored"
          (is (= 2 (count (:results result))))
          (is (= 2 (:question-count (:aggregate result)))))
        (testing "all scores correct (mock LLM)"
          (is (every? #(= :correct (:full-score %)) (:results result)))
          (is (every? #(= :correct (:raw-score %)) (:results result))))
        (testing "checkpoint file exists and parses"
          (let [cp (bench/checkpoint-read (:checkpoint-path result))]
            (is (= (:run-id result) (:run-id cp)))
            (is (= 8 (count (:stages cp))) "2 questions × 4 stages")
            (is (some? (:aggregate cp)))))
        ;; Cleanup
        (doseq [f (reverse (file-seq (io/file dir)))]
          (.delete f))))))

(deftest simulated-interruption-preserves-checkpoint
  (with-bench-mocks {}
    (let [dir        (str (io/file (System/getProperty "java.io.tmpdir")
                                   (str "bench-intr-" (System/currentTimeMillis))))
          calls      (atom 0)
          ;; Throw after 5 calls (first question = 4 calls, then 1 more call into second question)
          failing-llm (throwing-mock-llm calls 5)]
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (bench/run-benchmark! nil "." failing-llm :checkpoint-dir dir :concurrency 1)))
        ;; Find the checkpoint file
        (let [cp-files (->> (io/file dir) .listFiles seq (filter #(str/ends-with? (.getName %) ".edn")))]
          (is (= 1 (count cp-files)) "One checkpoint file exists")
          (when (seq cp-files)
            (let [cp (bench/checkpoint-read (str (first cp-files)))]
              (is (= 5 (count (:stages cp)))
                  "5 stages completed before failure (4 for q1 + 1 answer for q2)")
              (is (nil? (:aggregate cp)) "Aggregate not computed on interruption"))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

;; --- Tier 0: Resume and compatibility ---

(deftest validate-resume-compatibility-match
  (let [cp {:metadata {:repo-path "ring" :commit-sha "abc" :question-set-hash "def" :model-config "m1"}}
        cfg {:repo-path "ring" :commit-sha "abc" :question-set-hash "def" :model-config "m1"}]
    (is (= {:ok true} (bench/validate-resume-compatibility cp cfg)))))

(deftest validate-resume-compatibility-mismatch
  (let [cp  {:metadata {:repo-path "ring" :commit-sha "abc" :question-set-hash "def" :model-config "m1"}}
        cfg {:repo-path "ring" :commit-sha "xyz" :question-set-hash "def" :model-config "m1"}
        result (bench/validate-resume-compatibility cp cfg)]
    (is (contains? result :mismatches))
    (is (= 1 (count (:mismatches result))))
    (is (= :commit-sha (:field (first (:mismatches result)))))))

(deftest validate-resume-compatibility-multiple-mismatches
  (let [cp  {:metadata {:repo-path "ring" :commit-sha "abc" :question-set-hash "def" :model-config "m1"}}
        cfg {:repo-path "other" :commit-sha "xyz" :question-set-hash "def" :model-config "m1"}
        result (bench/validate-resume-compatibility cp cfg)]
    (is (= 2 (count (:mismatches result))))
    (is (= #{:repo-path :commit-sha} (set (map :field (:mismatches result)))))))

(deftest find-checkpoint-latest
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-find-" (System/currentTimeMillis)))
        id-a "1000-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        id-b "2000-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
    (try
      (.mkdirs dir)
      (spit (io/file dir (str id-a ".edn")) "{}")
      (spit (io/file dir (str id-b ".edn")) "{}")
      (is (str/ends-with? (bench/find-checkpoint (str dir) "latest")
                          (str id-b ".edn")))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest find-checkpoint-specific-run-id
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-find2-" (System/currentTimeMillis)))
        id-a "1000-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        id-b "2000-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
    (try
      (.mkdirs dir)
      (spit (io/file dir (str id-a ".edn")) "{}")
      (spit (io/file dir (str id-b ".edn")) "{}")
      (is (str/ends-with? (bench/find-checkpoint (str dir) id-a)
                          (str id-a ".edn")))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest find-checkpoint-not-found
  (let [dir    (io/file (System/getProperty "java.io.tmpdir")
                        (str "bench-find3-" (System/currentTimeMillis)))
        id-any "9999-cccccccc-cccc-cccc-cccc-cccccccccccc"]
    (try
      (.mkdirs dir)
      (is (nil? (bench/find-checkpoint (str dir) "latest")))
      (is (nil? (bench/find-checkpoint (str dir) id-any)))
      (finally
        (.delete dir)))))

(deftest find-checkpoint-rejects-invalid-run-id
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "bench-find4-" (System/currentTimeMillis)))]
    (try
      (.mkdirs dir)
      (is (thrown? clojure.lang.ExceptionInfo
                   (bench/find-checkpoint (str dir) "../../../etc/passwd")))
      (is (thrown? clojure.lang.ExceptionInfo
                   (bench/find-checkpoint (str dir) "nonexistent")))
      (finally
        (.delete dir)))))

(deftest find-checkpoint-no-dir
  (is (nil? (bench/find-checkpoint "/tmp/nonexistent-dir-xyz" "latest"))))

;; --- Tier 1: Resume integration ---

(deftest resume-skips-completed-stages
  (with-bench-mocks {}
    (let [dir   (str (io/file (System/getProperty "java.io.tmpdir")
                              (str "bench-resume-" (System/currentTimeMillis))))
          calls (atom 0)
          counting-llm (fn [prompt]
                         (swap! calls inc)
                         {:text (if (str/includes? prompt "Score this answer")
                                  (pr-str {:score :correct :reasoning "Mock judge"})
                                  "Mock answer")
                          :usage mock-usage})
          ;; First run: interrupted after 5 calls (q1 complete + q2 query-answer)
          fail-calls   (atom 0)
          failing-llm  (throwing-mock-llm fail-calls 5)]
      (try
        ;; Run until failure
        (try (bench/run-benchmark! nil "." failing-llm :checkpoint-dir dir :concurrency 1)
             (catch Exception _))
        ;; Load checkpoint
        (let [cp-path (bench/find-checkpoint dir "latest")
              cp      (bench/checkpoint-read cp-path)
              _       (is (= 5 (count (:stages cp))))
              ;; Resume with counting mock
              result  (bench/run-benchmark! nil "." counting-llm
                                            :checkpoint-dir dir
                                            :resume-checkpoint cp
                                            :concurrency 1)]
          ;; Should only make 3 more calls (q2: query-judge, raw-answer, raw-judge)
          (is (= 3 @calls) "Resume should only execute remaining stages")
          (is (= 2 (count (:results result))))
          (is (= 2 (:question-count (:aggregate result)))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest resume-all-complete-no-llm-calls
  (with-bench-mocks {:questions [{:id :t01 :question "Q1?" :category :test
                                  :query-name "test" :rubric "r1"}]}
    (let [dir   (str (io/file (System/getProperty "java.io.tmpdir")
                              (str "bench-allcomplete-" (System/currentTimeMillis))))
          calls (atom 0)
          ;; Checkpoint with all stages complete
          cp    {:run-id   "test-run"
                 :metadata {:repo-path "." :commit-sha "abc123"
                            :question-set-hash (bench/question-set-hash
                                                [{:id :t01 :question "Q1?" :category :test
                                                  :query-name "test" :rubric "r1"}])
                            :model-config "claude-sonnet-4-20250514"
                            :started-at (java.util.Date.)
                            :budget {:max-questions nil :stop-after-ms nil}}
                 :stages   {[:t01 :full :answer] {:status :ok :result "qa"}
                            [:t01 :full :judge]  {:status :ok :result {:score :correct :reasoning "g"}}
                            [:t01 :raw :answer]   {:status :ok :result "ra"}
                            [:t01 :raw :judge]    {:status :ok :result {:score :partial :reasoning "ok"}}}
                 :aggregate nil}
          counting-llm (fn [_] (swap! calls inc) {:text "should not be called" :usage mock-usage})]
      (try
        (let [result (bench/run-benchmark! nil "." counting-llm
                                           :checkpoint-dir dir
                                           :resume-checkpoint cp
                                           :concurrency 1)]
          (is (= 0 @calls) "No LLM calls when all stages complete")
          (is (= 1 (count (:results result))))
          (is (= :correct (:full-score (first (:results result)))))
          (is (= :partial (:raw-score (first (:results result))))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest incompatible-resume-detected
  (let [cp  {:run-id   "old-run"
             :metadata {:repo-path "ring" :commit-sha "old-sha"
                        :question-set-hash "hash1" :model-config "m1"}
             :stages   {}
             :aggregate nil}
        cfg {:repo-path "ring" :commit-sha "new-sha"
             :question-set-hash "hash1" :model-config "m1"}
        result (bench/validate-resume-compatibility cp cfg)]
    (is (not (:ok result)))
    (is (= [:commit-sha] (mapv :field (:mismatches result))))))

;; --- Tier 0: Budget ---

(deftest budget-check-no-limits
  (is (= :ok (bench/budget-check 12 0.0 {} 0))))

(deftest budget-check-max-questions-within
  ;; 8 stages completed, max-question-stages=12 (3 questions * 4 stages)
  (is (= :ok (bench/budget-check 8 0.0 {:max-questions 3} 0 12))))

(deftest budget-check-max-questions-reached
  (is (= {:exhausted :max-questions}
         (bench/budget-check 12 0.0 {:max-questions 3} 0 12))))

(deftest budget-check-max-questions-exceeded
  (is (= {:exhausted :max-questions}
         (bench/budget-check 16 0.0 {:max-questions 3} 0 12))))

(deftest budget-check-stop-after-within
  (is (= :ok (bench/budget-check 0 0.0 {:stop-after-ms 60000}
                                 (- (System/currentTimeMillis) 30000)))))

(deftest budget-check-stop-after-reached
  (is (= {:exhausted :stop-after}
         (bench/budget-check 0 0.0 {:stop-after-ms 1000}
                             (- (System/currentTimeMillis) 2000)))))

(deftest budget-check-max-questions-checked-first
  ;; When both limits are exceeded, max-questions is checked first
  (is (= {:exhausted :max-questions}
         (bench/budget-check 12 0.0 {:max-questions 3 :stop-after-ms 1000}
                             (- (System/currentTimeMillis) 2000) 12))))

(deftest budget-check-max-cost-within
  (is (= :ok (bench/budget-check 0 0.005 {:max-cost-usd 0.01} 0))))

(deftest budget-check-max-cost-exceeded
  (is (= {:exhausted :max-cost}
         (bench/budget-check 0 0.02 {:max-cost-usd 0.01} 0))))

(deftest budget-check-max-cost-after-max-questions
  ;; max-questions checked before max-cost
  (is (= {:exhausted :max-questions}
         (bench/budget-check 12 0.02 {:max-questions 3 :max-cost-usd 0.01} 0 12))))

;; --- Tier 1: Budget integration ---

(deftest max-questions-stops-at-correct-point
  (with-bench-mocks {:questions [{:id :t01 :question "Q1?" :category :test
                                  :query-name "test" :rubric "r1"}
                                 {:id :t02 :question "Q2?" :category :test
                                  :query-name "test" :rubric "r2"}
                                 {:id :t03 :question "Q3?" :category :test
                                  :query-name "test" :rubric "r3"}]}
    (let [dir   (str (io/file (System/getProperty "java.io.tmpdir")
                              (str "bench-budget-" (System/currentTimeMillis))))
          calls (atom 0)]
      (try
        (let [result (bench/run-benchmark! nil "." (fn [prompt]
                                                     (swap! calls inc)
                                                     {:text (if (str/includes? prompt "Score this answer")
                                                              (pr-str {:score :correct :reasoning "ok"})
                                                              "Mock answer")
                                                      :usage mock-usage})
                                           :checkpoint-dir dir
                                           :budget {:max-questions 2}
                                           :concurrency 1)]
          (is (= :max-questions (:stop-reason result)))
          (is (= 8 @calls) "2 questions × 4 stages = 8 LLM calls")
          (is (= 2 (count (:results result))))
          (is (= 2 (:question-count (:aggregate result))))
          ;; Verify checkpoint has exactly 8 stages
          (let [cp (bench/checkpoint-read (:checkpoint-path result))]
            (is (= 8 (count (:stages cp))))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest budget-limited-run-is-resumable
  (with-bench-mocks {:questions [{:id :t01 :question "Q1?" :category :test
                                  :query-name "test" :rubric "r1"}
                                 {:id :t02 :question "Q2?" :category :test
                                  :query-name "test" :rubric "r2"}
                                 {:id :t03 :question "Q3?" :category :test
                                  :query-name "test" :rubric "r3"}]}
    (let [dir      (str (io/file (System/getProperty "java.io.tmpdir")
                                 (str "bench-budgetresume-" (System/currentTimeMillis))))
          mock-fn  (fn [prompt]
                     {:text (if (str/includes? prompt "Score this answer")
                              (pr-str {:score :correct :reasoning "ok"})
                              "Mock answer")
                      :usage mock-usage})]
      (try
        ;; First run: budget stops at 1 question
        (let [r1 (bench/run-benchmark! nil "." mock-fn
                                       :checkpoint-dir dir
                                       :budget {:max-questions 1}
                                       :concurrency 1)]
          (is (= :max-questions (:stop-reason r1)))
          (is (= 1 (count (:results r1))))
          ;; Resume without budget limit
          (let [cp (bench/checkpoint-read (:checkpoint-path r1))
                r2 (bench/run-benchmark! nil "." mock-fn
                                         :checkpoint-dir dir
                                         :resume-checkpoint cp
                                         :concurrency 1)]
            (is (nil? (:stop-reason r2)))
            (is (= 3 (count (:results r2))) "All 3 questions completed")))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

;; --- Tier 0: Usage tracking ---

(deftest aggregate-usage-sums-stages
  (let [stages {[:q01 :full :answer] {:status :ok :result "a"
                                      :usage {:input-tokens 100 :output-tokens 50
                                              :cost-usd 0.01 :duration-ms 500}}
                [:q01 :full :judge]  {:status :ok :result {:score :correct}
                                      :usage {:input-tokens 200 :output-tokens 30
                                              :cost-usd 0.02 :duration-ms 300}}}
        agg (bench/aggregate-usage stages)]
    (is (= 300 (:input-tokens agg)))
    (is (= 80 (:output-tokens agg)))
    (is (< (abs (- 0.03 (:cost-usd agg))) 0.0001))
    (is (= 800 (:duration-ms agg)))))

(deftest aggregate-usage-empty-stages
  (let [agg (bench/aggregate-usage {})]
    (is (= 0 (:input-tokens agg)))
    (is (= 0 (:output-tokens agg)))
    (is (= 0.0 (:cost-usd agg)))
    (is (= 0 (:duration-ms agg)))))

(deftest aggregate-usage-missing-usage-graceful
  (let [stages {[:q01 :full :answer] {:status :ok :result "a"}
                [:q01 :full :judge]  {:status :ok :result {:score :correct}
                                      :usage {:input-tokens 50 :output-tokens 20
                                              :cost-usd 0.005 :duration-ms 200}}}
        agg (bench/aggregate-usage stages)]
    (is (= 50 (:input-tokens agg)))
    (is (= 20 (:output-tokens agg)))))

;; --- Tier 1: Usage in checkpoint ---

(deftest checkpoint-contains-usage
  (with-bench-mocks {:questions [{:id :t01 :question "Q1?" :category :test
                                  :query-name "test" :rubric "r1"}]}
    (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "bench-usage-" (System/currentTimeMillis))))]
      (try
        (let [result (bench/run-benchmark! nil "." mock-llm :checkpoint-dir dir :concurrency 1)
              cp     (bench/checkpoint-read (:checkpoint-path result))]
          (testing "total-usage in checkpoint"
            (is (some? (:total-usage cp)))
            (is (pos? (:input-tokens (:total-usage cp))))
            (is (pos? (:output-tokens (:total-usage cp)))))
          (testing "per-stage usage in checkpoint"
            (doseq [[_k stage] (:stages cp)]
              (is (some? (:usage stage)) "Each stage has :usage")))
          (testing "total-usage in result"
            (is (some? (:total-usage result)))
            (is (= (:total-usage cp) (:total-usage result)))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

;; --- Tier 0: Model/provider configurability ---

(deftest model-config-stored-in-checkpoint-metadata
  (with-bench-mocks {:questions [{:id :t01 :question "Q1?" :category :test
                                  :query-name "test" :rubric "r1"}]}
    (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "bench-modelcfg-" (System/currentTimeMillis))))
          mc  {:model "haiku" :judge-model "sonnet" :provider "claude"}]
      (try
        (let [result (bench/run-benchmark! nil "." mock-llm
                                           :checkpoint-dir dir
                                           :model-config mc
                                           :concurrency 1)
              cp     (bench/checkpoint-read (:checkpoint-path result))]
          (is (= mc (get-in cp [:metadata :model-config]))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest resume-rejects-mismatched-model-config
  (let [mc-a {:model "sonnet" :judge-model "sonnet" :provider "claude"}
        mc-b {:model "sonnet" :judge-model "sonnet" :provider "glm"}
        cp   {:metadata {:repo-path "ring" :commit-sha "abc"
                         :question-set-hash "def" :model-config mc-a}}
        cfg  {:repo-path "ring" :commit-sha "abc"
              :question-set-hash "def" :model-config mc-b}
        result (bench/validate-resume-compatibility cp cfg)]
    (is (not (:ok result)))
    (is (= [:model-config] (mapv :field (:mismatches result))))))

(deftest judge-llm-used-for-judge-stages
  (with-bench-mocks {:questions [{:id :t01 :question "Q1?" :category :test
                                  :query-name "test" :rubric "r1"}]}
    (let [dir          (str (io/file (System/getProperty "java.io.tmpdir")
                                     (str "bench-judgellm-" (System/currentTimeMillis))))
          answer-calls (atom 0)
          judge-calls  (atom 0)
          answer-fn    (fn [_p] (swap! answer-calls inc)
                         {:text "Mock answer" :usage mock-usage})
          judge-fn     (fn [_p] (swap! judge-calls inc)
                         {:text (pr-str {:score :correct :reasoning "ok"})
                          :usage mock-usage})]
      (try
        (bench/run-benchmark! nil "." answer-fn
                              :judge-llm judge-fn
                              :checkpoint-dir dir
                              :concurrency 1)
        (is (= 2 @answer-calls) "2 answer stages (query + raw)")
        (is (= 2 @judge-calls) "2 judge stages (query + raw)")
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

;; --- Tier 0: Prompt hashes ---

(deftest checkpoint-contains-prompt-hashes
  (with-bench-mocks {:questions [{:id :t01 :question "Q1?" :category :test
                                  :query-name "test" :rubric "r1"}]}
    (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "bench-hash-" (System/currentTimeMillis))))]
      (try
        (let [result (bench/run-benchmark! nil "." mock-llm :checkpoint-dir dir :concurrency 1)
              cp     (bench/checkpoint-read (:checkpoint-path result))]
          (is (string? (get-in cp [:metadata :rubric-hash])))
          (is (string? (get-in cp [:metadata :answer-prompt-hash])))
          (is (= 64 (count (get-in cp [:metadata :rubric-hash])))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest resume-rejects-changed-rubric-hash
  (let [cp  {:metadata {:repo-path "ring" :commit-sha "abc"
                        :question-set-hash "def" :model-config {:provider "claude"}
                        :rubric-hash "old-hash" :answer-prompt-hash "ap-hash"}}
        cfg {:repo-path "ring" :commit-sha "abc"
             :question-set-hash "def" :model-config {:provider "claude"}
             :rubric-hash "new-hash" :answer-prompt-hash "ap-hash"}
        result (bench/validate-resume-compatibility cp cfg)]
    (is (not (:ok result)))
    (is (= [:rubric-hash] (mapv :field (:mismatches result))))))

(deftest resume-ignores-missing-hashes-in-old-checkpoint
  (let [cp  {:metadata {:repo-path "ring" :commit-sha "abc"
                        :question-set-hash "def" :model-config {:provider "claude"}}}
        cfg {:repo-path "ring" :commit-sha "abc"
             :question-set-hash "def" :model-config {:provider "claude"}
             :rubric-hash "hash1" :answer-prompt-hash "hash2"}]
    (is (= {:ok true} (bench/validate-resume-compatibility cp cfg)))))

;; --- Tier 1: Cost budget integration ---

(deftest max-cost-stops-run
  (with-bench-mocks {}
    (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "bench-maxcost-" (System/currentTimeMillis))))
          ;; Each call costs 0.001, 4 calls per question = 0.004
          ;; Budget of 0.003 should stop after first question (4 stages = $0.004)
          expensive-llm (fn [prompt]
                          {:text (if (str/includes? prompt "Score this answer")
                                   (pr-str {:score :correct :reasoning "ok"})
                                   "Mock answer")
                           :usage {:input-tokens 10 :output-tokens 5
                                   :cost-usd 0.001 :duration-ms 100}})]
      (try
        (let [result (bench/run-benchmark! nil "." expensive-llm
                                           :checkpoint-dir dir
                                           :budget {:max-cost-usd 0.003}
                                           :concurrency 1)]
          (is (= :max-cost (:stop-reason result)))
          (is (= 4 (count (:stages (bench/checkpoint-read (:checkpoint-path result))))))
          (is (= 1 (count (:results result)))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

;; --- Tier 1: Concurrent execution ---

(deftest concurrent-run-completes-all-stages
  (with-bench-mocks {}
    (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "bench-conc-" (System/currentTimeMillis))))]
      (try
        (let [result (bench/run-benchmark! nil "." mock-llm
                                           :checkpoint-dir dir
                                           :concurrency 2)]
          (testing "all stages completed"
            (let [cp (bench/checkpoint-read (:checkpoint-path result))]
              (is (= 8 (count (:stages cp))) "2 questions × 4 stages")))
          (testing "correct aggregate scores"
            (is (= 2 (:question-count (:aggregate result))))
            (is (every? #(= :correct (:full-score %)) (:results result)))
            (is (every? #(= :correct (:raw-score %)) (:results result))))
          (testing "run-id and checkpoint-path returned"
            (is (string? (:run-id result)))
            (is (string? (:checkpoint-path result)))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest resume-after-concurrent-run
  (with-bench-mocks {}
    (let [dir        (str (io/file (System/getProperty "java.io.tmpdir")
                                   (str "bench-concresume-" (System/currentTimeMillis))))
          fail-calls (atom 0)
          failing-llm (throwing-mock-llm fail-calls 5)]
      (try
        ;; Run with concurrency until failure
        (try (bench/run-benchmark! nil "." failing-llm :checkpoint-dir dir :concurrency 2)
             (catch Exception _))
        ;; Load checkpoint and resume with different concurrency
        (let [cp-path (bench/find-checkpoint dir "latest")
              cp      (bench/checkpoint-read cp-path)
              _       (is (pos? (count (:stages cp))) "Some stages completed before failure")
              calls   (atom 0)
              counting-llm (fn [prompt]
                             (swap! calls inc)
                             {:text (if (str/includes? prompt "Score this answer")
                                      (pr-str {:score :correct :reasoning "Mock judge"})
                                      "Mock answer")
                              :usage mock-usage})
              result  (bench/run-benchmark! nil "." counting-llm
                                            :checkpoint-dir dir
                                            :resume-checkpoint cp
                                            :concurrency 1)]
          (is (= 2 (count (:results result))) "All questions completed after resume")
          (is (= (- 8 (count (:stages cp))) @calls) "Only remaining stages executed"))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest budget-with-concurrency
  (with-bench-mocks {}
    (let [dir (str (io/file (System/getProperty "java.io.tmpdir")
                            (str "bench-concbudget-" (System/currentTimeMillis))))
          expensive-llm (fn [prompt]
                          {:text (if (str/includes? prompt "Score this answer")
                                   (pr-str {:score :correct :reasoning "ok"})
                                   "Mock answer")
                           :usage {:input-tokens 10 :output-tokens 5
                                   :cost-usd 0.001 :duration-ms 100}})]
      (try
        (let [result (bench/run-benchmark! nil "." expensive-llm
                                           :checkpoint-dir dir
                                           :budget {:max-cost-usd 0.003}
                                           :concurrency 2)]
          (is (= :max-cost (:stop-reason result)))
          ;; With concurrency 2, up to 1 extra pair may complete beyond budget
          (let [cp (bench/checkpoint-read (:checkpoint-path result))]
            (is (<= 4 (count (:stages cp)) 8)
                "At least 1 question's stages, at most all")))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest llm-failure-during-concurrent-run
  (with-bench-mocks {}
    (let [dir   (str (io/file (System/getProperty "java.io.tmpdir")
                              (str "bench-concfail-" (System/currentTimeMillis))))
          calls (atom 0)
          failing-llm (throwing-mock-llm calls 3)]
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (bench/run-benchmark! nil "." failing-llm
                                           :checkpoint-dir dir
                                           :concurrency 2)))
        ;; Checkpoint saved with completed stages
        (let [cp-files (->> (io/file dir) .listFiles seq
                            (filter #(str/ends-with? (.getName %) ".edn")))]
          (is (= 1 (count cp-files)) "Checkpoint file exists")
          (when (seq cp-files)
            (let [cp (bench/checkpoint-read (str (first cp-files)))]
              (is (pos? (count (:stages cp))) "Some stages saved before failure"))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest concurrency-1-matches-sequential
  (with-bench-mocks {}
    (let [dir1   (str (io/file (System/getProperty "java.io.tmpdir")
                               (str "bench-seq1-" (System/currentTimeMillis))))
          dir2   (str (io/file (System/getProperty "java.io.tmpdir")
                               (str "bench-seq2-" (System/currentTimeMillis))))
          calls1 (atom [])
          calls2 (atom [])
          tracking-llm (fn [calls-atom]
                         (fn [prompt]
                           (swap! calls-atom conj (if (str/includes? prompt "Score this answer")
                                                    :judge :answer))
                           {:text (if (str/includes? prompt "Score this answer")
                                    (pr-str {:score :correct :reasoning "ok"})
                                    "Mock answer")
                            :usage mock-usage}))]
      (try
        (let [r1 (bench/run-benchmark! nil "." (tracking-llm calls1)
                                       :checkpoint-dir dir1 :concurrency 1)
              r2 (bench/run-benchmark! nil "." (tracking-llm calls2)
                                       :checkpoint-dir dir2 :concurrency 1)]
          (testing "same call count"
            (is (= (count @calls1) (count @calls2))))
          (testing "same call ordering"
            (is (= @calls1 @calls2)))
          (testing "same results"
            (is (= (count (:results r1)) (count (:results r2))))
            (is (= (:aggregate r1) (:aggregate r2)))))
        (finally
          (doseq [d [dir1 dir2]
                  f (reverse (file-seq (io/file d)))]
            (.delete f)))))))

;; --- Tier 0: Deterministic scoring ---

(deftest deterministic-score-q01-correct
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["ring/core.clj" :complex]
                        ["ring/handler.clj" :very-complex]
                        ["ring/util.clj" :trivial]]})]
    (let [q      {:id :q01 :query-name "files-by-complexity" :scoring :deterministic}
          answer "The complex files are ring/core.clj and ring/handler.clj."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :correct (:score result))))))

(deftest deterministic-score-q01-partial
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["ring/core.clj" :complex]
                        ["ring/handler.clj" :very-complex]
                        ["ring/util.clj" :trivial]]})]
    (let [q      {:id :q01 :query-name "files-by-complexity" :scoring :deterministic}
          answer "The complex file is ring/core.clj."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :partial (:score result))))))

(deftest deterministic-score-q01-wrong
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["ring/core.clj" :complex]
                        ["ring/handler.clj" :very-complex]
                        ["ring/adapter.clj" :complex]
                        ["ring/util.clj" :trivial]]})]
    (let [q      {:id :q01 :query-name "files-by-complexity" :scoring :deterministic}
          answer "I don't know which files are complex."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :wrong (:score result))))))

(deftest deterministic-score-q02-correct
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["ring/middleware/params.clj" :middleware]
                        ["ring/core.clj" :core]]})]
    (let [q      {:id :q02 :query-name "files-by-layer" :scoring :deterministic
                  :resolved-params {:target-file "ring/middleware/params.clj"}}
          answer "ring/middleware/params.clj is classified as middleware."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :correct (:score result))))))

(deftest deterministic-score-q02-wrong
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["ring/middleware/params.clj" :middleware]
                        ["ring/core.clj" :core]]})]
    (let [q      {:id :q02 :query-name "files-by-layer" :scoring :deterministic
                  :resolved-params {:target-file "ring/middleware/params.clj"}}
          answer "That file is in the utility layer."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :wrong (:score result))))))

(deftest deterministic-score-q03-correct
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["Alice" "alice@test.com" 50]
                        ["Bob" "bob@test.com" 30]
                        ["Carol" "carol@test.com" 20]
                        ["Dave" "dave@test.com" 5]]})]
    (let [q      {:id :q03 :query-name "top-contributors" :scoring :deterministic}
          answer "Top contributors: 1. Alice (50 commits), 2. Bob (30 commits), 3. Carol (20 commits)."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :correct (:score result))))))

(deftest deterministic-score-q03-partial
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["Alice" "alice@test.com" 50]
                        ["Bob" "bob@test.com" 30]
                        ["Carol" "carol@test.com" 20]]})]
    (let [q      {:id :q03 :query-name "top-contributors" :scoring :deterministic}
          answer "The top contributors are Alice and Bob."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :partial (:score result))))))

(deftest deterministic-score-q03-wrong
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["Alice" "alice@test.com" 50]
                        ["Bob" "bob@test.com" 30]
                        ["Carol" "carol@test.com" 20]]})]
    (let [q      {:id :q03 :query-name "top-contributors" :scoring :deterministic}
          answer "The top contributor is Dave."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :wrong (:score result))))))

(deftest questions-edn-has-scoring-on-single-hop
  (let [qs (bench/load-questions)]
    (doseq [q qs]
      (if (= :single-hop (:category q))
        (is (= :deterministic (:scoring q))
            (str (:id q) " should have :scoring :deterministic"))
        (is (nil? (:scoring q))
            (str (:id q) " should NOT have :scoring"))))))

(deftest run-stage-deterministic-no-llm-call
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["ring/core.clj" :complex]]})]
    (let [q       {:id :q01 :question "?" :query-name "files-by-complexity"
                   :scoring :deterministic :rubric "r" :category :single-hop}
          stages  {[:q01 :full :answer] {:status :ok :result "ring/core.clj is complex"}}
          llm-called (atom false)
          mock-llm   (fn [_] (reset! llm-called true) {:text "" :usage llm/zero-usage})
          result  (bench/run-stage [:q01 :full :judge]
                                   {:question q :rubric-map {} :db nil :raw-ctx nil
                                    :stages stages :invoke-llm mock-llm :judge-llm mock-llm})]
      (is (false? @llm-called) "LLM should not be called for deterministic scoring")
      (is (= :correct (get-in result [:result :score])))
      (is (= 0 (get-in result [:usage :input-tokens]))))))

;; --- Tier 0: Mode-aware stage keys ---

(deftest mode-stage-keys-no-flags
  (let [q {:id :q01 :scoring :deterministic}]
    (is (= [[:q01 :raw :answer] [:q01 :raw :judge]
            [:q01 :full :answer] [:q01 :full :judge]]
           (bench/mode-stage-keys q {})))))

(deftest mode-stage-keys-skip-raw
  (let [q {:id :q01 :scoring :deterministic}]
    (is (= [[:q01 :full :answer] [:q01 :full :judge]]
           (bench/mode-stage-keys q {:skip-raw true})))))

(deftest mode-stage-keys-skip-judge-deterministic
  (let [q {:id :q01 :scoring :deterministic}]
    (is (= [[:q01 :raw :answer] [:q01 :raw :judge]
            [:q01 :full :answer] [:q01 :full :judge]]
           (bench/mode-stage-keys q {:skip-judge true}))
        "Deterministic questions keep judge stages even with --skip-judge")))

(deftest mode-stage-keys-skip-judge-llm
  (let [q {:id :q07}]
    (is (= [[:q07 :raw :answer] [:q07 :full :answer]]
           (bench/mode-stage-keys q {:skip-judge true}))
        "Non-deterministic questions lose judge stages with --skip-judge")))

(deftest mode-stage-keys-fast
  (let [q-det {:id :q01 :scoring :deterministic}
        q-llm {:id :q07}
        mode  {:layers [:full] :skip-judge true}]
    (is (= [[:q01 :full :answer] [:q01 :full :judge]]
           (bench/mode-stage-keys q-det mode)))
    (is (= [[:q07 :full :answer]]
           (bench/mode-stage-keys q-llm mode)))))

(deftest all-stage-keys-with-mode
  (let [qs [{:id :q01 :scoring :deterministic} {:id :q07}]
        mode {:layers [:full] :skip-judge true}]
    (is (= [[:q01 :full :answer] [:q01 :full :judge] [:q07 :full :answer]]
           (bench/all-stage-keys qs mode)))))

(deftest aggregate-scores-canonical-true
  (let [results [{:full-score :correct :raw-score :correct
                  :import-score :correct :enrich-score :correct :category :test}]
        agg     (bench/aggregate-scores results {:layers [:raw :import :enrich :full]})]
    (is (true? (:canonical agg)))))

(deftest aggregate-scores-canonical-false-with-default-layers
  (let [results [{:full-score :correct :raw-score :correct :category :test}]
        agg     (bench/aggregate-scores results)]
    (is (false? (:canonical agg))
        "Default layers [:raw :full] is not canonical — all 4 layers required")))

(deftest aggregate-scores-canonical-false-with-skip-raw
  (let [results [{:full-score :correct :category :test}]
        agg     (bench/aggregate-scores results {:skip-raw true})]
    (is (false? (:canonical agg)))
    (is (nil? (:raw-mean agg)))))

(deftest aggregate-scores-skip-judge-no-scored
  (let [results [{:full-score :wrong :category :test}]
        agg     (bench/aggregate-scores results {:skip-judge true})]
    (is (false? (:canonical agg)))))

(deftest validate-resume-mode-mismatch
  (let [cp  {:metadata {:repo-path "ring" :commit-sha "abc"
                        :question-set-hash "def" :model-config "m1"
                        :mode {:skip-raw true}}}
        cfg {:repo-path "ring" :commit-sha "abc"
             :question-set-hash "def" :model-config "m1"
             :mode {}}
        result (bench/validate-resume-compatibility cp cfg)]
    (is (contains? result :mismatches))
    (is (some #(= :mode (:field %)) (:mismatches result)))))

(deftest validate-resume-mode-match
  (let [cp  {:metadata {:repo-path "ring" :commit-sha "abc"
                        :question-set-hash "def" :model-config "m1"
                        :mode {:skip-raw true}}}
        cfg {:repo-path "ring" :commit-sha "abc"
             :question-set-hash "def" :model-config "m1"
             :mode {:skip-raw true}}]
    (is (= {:ok true} (bench/validate-resume-compatibility cp cfg)))))

;; --- Tier 1: Integration with --fast ---

(deftest fast-mode-only-query-answers-and-deterministic-judges
  (with-redefs [bench/load-questions (fn [] [{:id :q01 :question "Q1?" :category :single-hop
                                              :query-name "files-by-complexity" :rubric "r1"
                                              :scoring :deterministic}
                                             {:id :q07 :question "Q2?" :category :architectural
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_meta-db _db _qn] "mock query context")
                bench/raw-context    (fn [_rp] (throw (ex-info "Should not be called" {})))
                bench/repo-head-sha  (fn [_rp] "abc123")
                bench/pick-benchmark-targets (fn [_meta-db _db] {:target-file "mock/target.clj"})
                query/run-named-query (fn [_meta-db _db _qn]
                                        {:ok [["ring/core.clj" :complex]]})]
    (let [dir   (str (io/file (System/getProperty "java.io.tmpdir")
                              (str "bench-fast-" (System/currentTimeMillis))))
          calls (atom 0)
          counting-llm (fn [prompt]
                         (swap! calls inc)
                         {:text (if (str/includes? prompt "Score this answer")
                                  (pr-str {:score :correct :reasoning "Mock"})
                                  "ring/core.clj is complex")
                          :usage llm/zero-usage})]
      (try
        (let [result (bench/run-benchmark! nil "." counting-llm
                                           :checkpoint-dir dir
                                           :mode {:layers [:full] :skip-judge true}
                                           :concurrency 1)]
          (testing "only full-answer stages invoke LLM"
            (is (= 2 @calls) "2 answer calls (1 per question, full-only)"))
          (testing "deterministic question scored"
            (let [q01 (first (filter #(= :q01 (:id %)) (:results result)))]
              (is (some? q01))
              (is (= :correct (:full-score q01)))))
          (testing "non-deterministic question not scored"
            (let [q07 (first (filter #(= :q07 (:id %)) (:results result)))]
              (is (some? q07) "q07 should appear with answer-only stages")
              (is (nil? (:full-score q07)) "No judge → score is nil (not :wrong)")))
          (testing "aggregate is non-canonical"
            (is (false? (get-in result [:aggregate :canonical]))))
          (testing "checkpoint metadata has mode"
            (let [cp (bench/checkpoint-read (:checkpoint-path result))]
              (is (= {:layers [:full] :skip-judge true}
                     (get-in cp [:metadata :mode]))))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

;; --- Tier 0: Canary evaluation ---

(deftest canary-question-ids-are-q01-q02
  (is (= #{:q01 :q02} bench/canary-question-ids)))

(deftest canary-evaluate-pass
  (let [results [{:id :q01 :full-score :correct}
                 {:id :q02 :full-score :correct}]]
    (is (= :pass (:status (bench/canary-evaluate results))))))

(deftest canary-evaluate-pass-one-wrong
  (let [results [{:id :q01 :full-score :wrong}
                 {:id :q02 :full-score :correct}]]
    (is (= :pass (:status (bench/canary-evaluate results)))
        "Only warns when ALL canary questions are wrong")))

(deftest canary-evaluate-warn
  (let [results [{:id :q01 :full-score :wrong}
                 {:id :q02 :full-score :wrong}]]
    (is (= :warn (:status (bench/canary-evaluate results))))))

;; --- Tier 1: Canary integration ---

(deftest canary-runs-two-phase-execution
  (with-redefs [bench/load-questions (fn [] [{:id :q01 :question "Q1?" :category :single-hop
                                              :query-name "files-by-complexity" :rubric "r1"
                                              :scoring :deterministic}
                                             {:id :q02 :question "Q2?" :category :single-hop
                                              :query-name "files-by-layer" :rubric "r2"
                                              :scoring :deterministic}
                                             {:id :q04 :question "Q3?" :category :multi-hop
                                              :query-name "test" :rubric "r3"}])
                bench/query-context  (fn [_meta-db _db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")
                bench/pick-benchmark-targets (fn [_meta-db _db] {:target-file "mock/target.clj"})
                query/run-named-query (fn [_meta-db _db qn]
                                        (case qn
                                          "files-by-complexity"
                                          {:ok [["ring/core.clj" :complex]]}
                                          "files-by-layer"
                                          {:ok [["ring/middleware/params.clj" :middleware]]}
                                          {:ok []}))]
    (let [dir     (str (io/file (System/getProperty "java.io.tmpdir")
                                (str "bench-canary-" (System/currentTimeMillis))))
          order   (atom [])
          mock-llm (fn [prompt]
                     (let [qid (cond
                                 (str/includes? prompt "Q1?") :q01
                                 (str/includes? prompt "Q2?") :q02
                                 (str/includes? prompt "Q3?") :q04
                                 :else :unknown)]
                       (swap! order conj qid))
                     {:text (if (str/includes? prompt "Score this answer")
                              (pr-str {:score :correct :reasoning "Mock"})
                              "ring/core.clj is complex with middleware layer")
                      :usage mock-usage})]
      (try
        (let [stderr-output (with-out-str
                              (binding [*err* *out*]
                                (bench/run-benchmark! nil "." mock-llm
                                                      :checkpoint-dir dir
                                                      :canary true
                                                      :concurrency 1)))]
          (testing "canary log messages present"
            (is (str/includes? stderr-output "bench/canary-start"))
            (is (or (str/includes? stderr-output "bench/canary-pass")
                    (str/includes? stderr-output "bench/canary-warn"))))
          (testing "canary questions processed before remaining"
            (let [filtered (filterv #{:q01 :q02 :q04} @order)
                  q04-idx  (.indexOf filtered :q04)]
              (is (pos? q04-idx) "q04 should appear after canary questions"))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

;; --- Tier 0: Rate gate ---

(deftest acquire-rate-gate-enforces-minimum-delay
  (let [gate (atom 0)
        delay-ms 50]
    (bench/acquire-rate-gate! gate delay-ms)
    (let [t1 (System/currentTimeMillis)]
      (bench/acquire-rate-gate! gate delay-ms)
      (let [elapsed (- (System/currentTimeMillis) t1)]
        (is (>= elapsed (- delay-ms 5))
            (str "Expected at least ~" delay-ms "ms delay, got " elapsed "ms"))))))

(deftest acquire-rate-gate-zero-delay-no-block
  (let [gate (atom 0)
        t1   (System/currentTimeMillis)]
    (bench/acquire-rate-gate! gate 0)
    (bench/acquire-rate-gate! gate 0)
    (let [elapsed (- (System/currentTimeMillis) t1)]
      (is (< elapsed 20) "Zero delay should not block"))))

(deftest acquire-rate-gate-concurrent-does-not-serialize-sleep
  (testing "Two threads waiting should overlap, not serialize (no lock held during sleep)"
    (let [gate     (atom 0)
          delay-ms 100
          _        (bench/acquire-rate-gate! gate delay-ms)
          t1       (System/currentTimeMillis)
          f1       (future (bench/acquire-rate-gate! gate delay-ms))
          f2       (future (bench/acquire-rate-gate! gate delay-ms))]
      @f1 @f2
      (let [elapsed (- (System/currentTimeMillis) t1)]
        (is (< elapsed (* delay-ms 3))
            (str "Concurrent gates should not serialize: " elapsed "ms for 2 calls"))))))

;; --- Tier 1: run-stage LLM path ---

(deftest run-stage-answer-with-mock-llm
  (let [q        {:id :t01 :question "What is Ring?" :query-name "files-by-complexity"
                  :rubric "r" :category :test}
        rubric   (bench/load-rubric)
        mock-fn  (fn [_prompt]
                   {:text "Ring is a web library" :usage mock-usage :resolved-model "mock-v1"})
        result   (with-redefs [bench/query-context (fn [_meta-db _db _qn] "mock context")]
                   (bench/run-stage [:t01 :full :answer]
                                    {:question q :rubric-map rubric :db nil :raw-ctx nil
                                     :stages {} :invoke-llm mock-fn :judge-llm mock-fn}))]
    (is (= :ok (:status result)))
    (is (= "Ring is a web library" (:result result)))
    (is (= "mock-v1" (:resolved-model result)))
    (is (some? (:completed-at result)))))

(deftest run-stage-judge-with-mock-llm
  (let [q        {:id :t01 :question "What is Ring?" :query-name "files-by-complexity"
                  :rubric "Explain the library" :category :test}
        rubric   (bench/load-rubric)
        stages   {[:t01 :full :answer] {:status :ok :result "Ring is a web library"}}
        mock-fn  (fn [_prompt]
                   {:text (pr-str {:score :correct :reasoning "Good answer"})
                    :usage mock-usage :resolved-model "mock-v1"})
        result   (bench/run-stage [:t01 :full :judge]
                                  {:question q :rubric-map rubric :db nil :raw-ctx nil
                                   :stages stages :invoke-llm mock-fn :judge-llm mock-fn})]
    (is (= :ok (:status result)))
    (is (= :correct (get-in result [:result :score])))
    (is (= "Good answer" (get-in result [:result :reasoning])))))

;; --- Tier 0: New deterministic scoring methods ---

(deftest deterministic-score-q05-found
  (with-redefs [query/run-named-query
                (fn [_meta-db _db qn]
                  (case qn
                    "files-by-complexity" {:ok [["a.clj" :trivial] ["b.clj" :complex]]}
                    "files-by-layer"      {:ok [["a.clj" :core] ["b.clj" :middleware]]}))]
    (let [q      {:id :q05 :query-name "files-by-complexity" :scoring :deterministic}
          answer "a.clj is both trivial and in the core layer."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :correct (:score result))))))

(deftest deterministic-score-q05-empty
  (with-redefs [query/run-named-query
                (fn [_meta-db _db qn]
                  (case qn
                    "files-by-complexity" {:ok [["a.clj" :complex]]}
                    "files-by-layer"      {:ok [["a.clj" :middleware]]}))]
    (let [q      {:id :q05 :query-name "files-by-complexity" :scoring :deterministic}
          answer "There are none that match both criteria."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :skipped (:score result))))))

(deftest deterministic-score-q06-correct
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["ring-core" "ring-util"]
                        ["ring-core" "ring-codec"]
                        ["ring-servlet" "ring-core"]]})]
    (let [q      {:id :q06 :query-name "component-dependencies" :scoring :deterministic}
          answer "ring-core has the most transitive dependencies."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :correct (:score result))))))

(deftest deterministic-score-q25-correct
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [["heavy.clj" 15] ["medium.clj" 8] ["light.clj" 3]]})]
    (let [q      {:id :q25 :query-name "dependency-hotspots" :scoring :deterministic}
          answer "heavy.clj (15 deps), medium.clj (8 deps), light.clj (3 deps)"
          result (bench/deterministic-score q nil nil answer)]
      (is (= :correct (:score result))))))

(deftest deterministic-score-q38-correct
  (with-redefs [query/run-named-query
                (fn [_meta-db _db _qn]
                  {:ok [[:feat 50] [:fix 30] [:refactor 10]]})]
    (let [q      {:id :q38 :query-name "commit-kinds" :scoring :deterministic}
          answer "The most common commit type is feat (50), followed by fix (30), then refactor (10)."
          result (bench/deterministic-score q nil nil answer)]
      (is (= :correct (:score result))))))

;; --- Tier 0: Per-tier aggregation ---

(deftest aggregate-scores-per-tier
  (let [results [{:full-score :correct :raw-score :correct :category :single-hop
                  :scoring :deterministic}
                 {:full-score :partial :raw-score :wrong :category :single-hop
                  :scoring :deterministic}
                 {:full-score :correct :raw-score :partial :category :multi-hop
                  :scoring nil}]
        agg     (bench/aggregate-scores results)]
    (is (= 2 (:deterministic-count agg)))
    (is (= 1 (:llm-judged-count agg)))
    (is (= 0.75 (:deterministic-mean agg)))
    (is (= 1.0 (:llm-judged-mean agg)))))

;; --- Tier 0: Question param resolution ---

(deftest resolve-question-params-substitutes
  (let [qs [{:id :q02 :question "What layer is {{target-file}}?"}
            {:id :q07 :question "Describe architecture."}]
        resolved (bench/resolve-question-params qs {:target-file "foo/bar.clj"})]
    (is (= "What layer is foo/bar.clj?" (:question (first resolved))))
    (is (= {:target-file "foo/bar.clj"} (:resolved-params (first resolved))))
    (is (= "Describe architecture." (:question (second resolved))))
    (is (nil? (:resolved-params (second resolved))))))

;; --- Tier 0: stages->results propagates scoring ---

(deftest stages->results-includes-scoring
  (let [qs     [{:id :q01 :category :single-hop :query-name "test" :scoring :deterministic}]
        stages {[:q01 :full :answer] {:status :ok :result "qa"}
                [:q01 :full :judge]  {:status :ok :result {:score :correct :reasoning "good"}}
                [:q01 :raw :answer]   {:status :ok :result "ra"}
                [:q01 :raw :judge]    {:status :ok :result {:score :partial :reasoning "ok"}}}
        results (vec (bench/stages->results qs stages))]
    (is (= :deterministic (:scoring (first results))))))
