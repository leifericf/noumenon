(ns noumenon.benchmark-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [noumenon.benchmark :as bench]))

;; --- Tier 0: Pure function tests ---

(deftest load-questions-returns-10
  (let [qs (bench/load-questions)]
    (is (= 10 (count qs)))
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
  (is (= 0.0 (bench/score-value :unknown))))

(deftest aggregate-scores-basic
  (let [results [{:query-score :correct :raw-score :partial :category :single-hop}
                 {:query-score :partial :raw-score :wrong   :category :single-hop}
                 {:query-score :correct :raw-score :correct :category :multi-hop}]
        agg     (bench/aggregate-scores results)]
    (is (= 3 (:question-count agg)))
    (is (> (:query-mean agg) (:raw-mean agg)))
    (is (contains? (:per-category agg) :single-hop))
    (is (contains? (:per-category agg) :multi-hop))))

(deftest aggregate-scores-empty
  (let [agg (bench/aggregate-scores [])]
    (is (= 0 (:question-count agg)))
    (is (= 0.0 (:query-mean agg)))))

(deftest aggregate-scores-partial-results
  (let [results [{:query-score :correct :raw-score :partial :category :single-hop}
                 {:query-score :correct :raw-score :correct :category :multi-hop}
                 {:query-score :partial :raw-score :wrong   :category :single-hop}]
        agg     (bench/aggregate-scores results)]
    (is (= 3 (:question-count agg)))
    (is (< (abs (- (:query-mean agg) (/ 5.0 6))) 0.001))
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

;; --- Tier 0: Checkpoint infrastructure ---

(deftest generate-run-id-format
  (let [id (bench/generate-run-id)]
    (is (string? id))
    (is (re-matches #"\d+-[0-9a-f]{4}" id))))

(deftest generate-run-id-unique
  (let [ids (repeatedly 100 bench/generate-run-id)]
    (is (= (count ids) (count (set ids))))))

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
        cp   {:run-id   "123-abcd"
              :metadata {:repo-path "test" :commit-sha "abc"}
              :stages   {[:q01 :query :answer] {:status :ok :result "answer" :completed-at (java.util.Date.)}
                         [:q01 :query :judge]  {:status :ok :result {:score :correct :reasoning "good"}
                                                :completed-at (java.util.Date.)}}
              :aggregate nil}]
    (try
      (bench/checkpoint-write path cp)
      (is (.exists (io/file path)))
      (is (not (.exists (io/file (str path ".tmp")))))
      (let [loaded (bench/checkpoint-read path)]
        (is (= "123-abcd" (:run-id loaded)))
        (is (= 2 (count (:stages loaded))))
        (is (= "answer" (get-in loaded [:stages [:q01 :query :answer] :result])))
        (is (= :correct (get-in loaded [:stages [:q01 :query :judge] :result :score]))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest checkpoint-write-is-atomic
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-atomic-" (System/currentTimeMillis)))
        path (str (io/file dir "test.edn"))]
    (try
      (bench/checkpoint-write path {:run-id "1" :stages {}})
      (bench/checkpoint-write path {:run-id "2" :stages {}})
      (is (= "2" (:run-id (bench/checkpoint-read path))))
      (is (not (.exists (io/file (str path ".tmp")))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest concurrent-checkpoint-writes-no-lost-stages
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "bench-concurrent-" (System/currentTimeMillis)))
        path (str (io/file dir "test.edn"))
        n    20
        cp   (atom {:run-id "concurrent-test" :stages {}})
        _    (.mkdirs dir)]
    (try
      ;; N futures each add a unique stage and write checkpoint
      (let [futures (mapv (fn [i]
                            (future
                              (let [stage-key [(keyword (str "q" i)) :query :answer]]
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
          (let [stage-key [(keyword (str "q" i)) :query :answer]]
            (is (contains? (:stages loaded) stage-key)
                (str "Missing stage " stage-key)))))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest stage-keys-for-question
  (let [q    {:id :q01 :question "?" :category :single-hop :query-name "test" :rubric "r"}
        keys (bench/stage-keys q)]
    (is (= 4 (count keys)))
    (is (= [[:q01 :query :answer]
            [:q01 :query :judge]
            [:q01 :raw :answer]
            [:q01 :raw :judge]]
           keys))))

(deftest all-stage-keys-ordering
  (let [qs   [{:id :q01} {:id :q02}]
        keys (bench/all-stage-keys qs)]
    (is (= 8 (count keys)))
    (is (= [:q01 :q01 :q01 :q01 :q02 :q02 :q02 :q02]
           (mapv first keys)))))

(deftest stages->results-complete-question
  (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
        stages {[:q01 :query :answer] {:status :ok :result "qa"}
                [:q01 :query :judge]  {:status :ok :result {:score :correct :reasoning "good"}}
                [:q01 :raw :answer]   {:status :ok :result "ra"}
                [:q01 :raw :judge]    {:status :ok :result {:score :partial :reasoning "ok"}}}
        results (vec (bench/stages->results qs stages))]
    (is (= 1 (count results)))
    (is (= :q01 (:id (first results))))
    (is (= "qa" (:query-answer (first results))))
    (is (= :correct (:query-score (first results))))
    (is (= "ra" (:raw-answer (first results))))
    (is (= :partial (:raw-score (first results))))))

(deftest stages->results-incomplete-question-excluded
  (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
        stages {[:q01 :query :answer] {:status :ok :result "qa"}
                [:q01 :query :judge]  {:status :ok :result {:score :correct :reasoning "good"}}}
        results (vec (bench/stages->results qs stages))]
    (is (= 0 (count results)))))

(deftest stages->results-nil-judge-defaults-to-wrong
  (let [qs     [{:id :q01 :category :single-hop :query-name "test"}]
        stages {[:q01 :query :answer] {:status :ok :result "qa"}
                [:q01 :query :judge]  {:status :ok :result nil}
                [:q01 :raw :answer]   {:status :ok :result "ra"}
                [:q01 :raw :judge]    {:status :ok :result nil}}
        results (vec (bench/stages->results qs stages))]
    (is (= 1 (count results)))
    (is (= :wrong (:query-score (first results))))
    (is (= :wrong (:raw-score (first results))))))

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
    (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                                :query-name "test" :rubric "r1"}
                                               {:id :t02 :question "Q2?" :category :test
                                                :query-name "test" :rubric "r2"}])
                  bench/query-context  (fn [_db _qn] "mock query context")
                  bench/raw-context    (fn [_rp] "mock raw context")
                  bench/repo-head-sha  (fn [_rp] "abc123")]
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
          (is (every? #(= :correct (:query-score %)) (:results result)))
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "bench-find-" (System/currentTimeMillis)))]
    (try
      (.mkdirs dir)
      (spit (io/file dir "1000-aaaa.edn") "{}")
      (spit (io/file dir "2000-bbbb.edn") "{}")
      (is (str/ends-with? (bench/find-checkpoint (str dir) "latest") "2000-bbbb.edn"))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest find-checkpoint-specific-run-id
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "bench-find2-" (System/currentTimeMillis)))]
    (try
      (.mkdirs dir)
      (spit (io/file dir "1000-aaaa.edn") "{}")
      (spit (io/file dir "2000-bbbb.edn") "{}")
      (is (str/ends-with? (bench/find-checkpoint (str dir) "1000-aaaa") "1000-aaaa.edn"))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))))

(deftest find-checkpoint-not-found
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "bench-find3-" (System/currentTimeMillis)))]
    (try
      (.mkdirs dir)
      (is (nil? (bench/find-checkpoint (str dir) "latest")))
      (is (nil? (bench/find-checkpoint (str dir) "nonexistent")))
      (finally
        (.delete dir)))))

(deftest find-checkpoint-no-dir
  (is (nil? (bench/find-checkpoint "/tmp/nonexistent-dir-xyz" "latest"))))

;; --- Tier 1: Resume integration ---

(deftest resume-skips-completed-stages
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] (throw (ex-info "Should not be called" {})))
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
                 :stages   {[:t01 :query :answer] {:status :ok :result "qa"}
                            [:t01 :query :judge]  {:status :ok :result {:score :correct :reasoning "g"}}
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
          (is (= :correct (:query-score (first (:results result)))))
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
  (is (= :ok (bench/budget-check 8 0.0 {:max-questions 3} 0))))

(deftest budget-check-max-questions-reached
  (is (= {:exhausted :max-questions}
         (bench/budget-check 12 0.0 {:max-questions 3} 0))))

(deftest budget-check-max-questions-exceeded
  (is (= {:exhausted :max-questions}
         (bench/budget-check 16 0.0 {:max-questions 3} 0))))

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
                             (- (System/currentTimeMillis) 2000)))))

(deftest budget-check-max-cost-within
  (is (= :ok (bench/budget-check 0 0.005 {:max-cost-usd 0.01} 0))))

(deftest budget-check-max-cost-exceeded
  (is (= {:exhausted :max-cost}
         (bench/budget-check 0 0.02 {:max-cost-usd 0.01} 0))))

(deftest budget-check-max-cost-after-max-questions
  ;; max-questions checked before max-cost
  (is (= {:exhausted :max-questions}
         (bench/budget-check 12 0.02 {:max-questions 3 :max-cost-usd 0.01} 0))))

;; --- Tier 1: Budget integration ---

(deftest max-questions-stops-at-correct-point
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}
                                             {:id :t03 :question "Q3?" :category :test
                                              :query-name "test" :rubric "r3"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}
                                             {:id :t03 :question "Q3?" :category :test
                                              :query-name "test" :rubric "r3"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (let [stages {[:q01 :query :answer] {:status :ok :result "a"
                                       :usage {:input-tokens 100 :output-tokens 50
                                               :cost-usd 0.01 :duration-ms 500}}
                [:q01 :query :judge]  {:status :ok :result {:score :correct}
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
  (let [stages {[:q01 :query :answer] {:status :ok :result "a"}
                [:q01 :query :judge]  {:status :ok :result {:score :correct}
                                       :usage {:input-tokens 50 :output-tokens 20
                                               :cost-usd 0.005 :duration-ms 200}}}
        agg (bench/aggregate-usage stages)]
    (is (= 50 (:input-tokens agg)))
    (is (= 20 (:output-tokens agg)))))

;; --- Tier 1: Usage in checkpoint ---

(deftest checkpoint-contains-usage
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
            (is (every? #(= :correct (:query-score %)) (:results result)))
            (is (every? #(= :correct (:raw-score %)) (:results result))))
          (testing "run-id and checkpoint-path returned"
            (is (string? (:run-id result)))
            (is (string? (:checkpoint-path result)))))
        (finally
          (doseq [f (reverse (file-seq (io/file dir)))]
            (.delete f)))))))

(deftest resume-after-concurrent-run
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
  (with-redefs [bench/load-questions (fn [] [{:id :t01 :question "Q1?" :category :test
                                              :query-name "test" :rubric "r1"}
                                             {:id :t02 :question "Q2?" :category :test
                                              :query-name "test" :rubric "r2"}])
                bench/query-context  (fn [_db _qn] "mock query context")
                bench/raw-context    (fn [_rp] "mock raw context")
                bench/repo-head-sha  (fn [_rp] "abc123")]
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
