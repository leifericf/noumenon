(ns noumenon.longbench-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.benchmark :as bench]
            [noumenon.longbench :as lb]))

;; --- Tier 0: Pure function tests ---

;; --- filter-code-repo-questions ---

(deftest filter-code-repo-questions-selects-correct-domain
  (let [items [{:_id "1" :domain "Code Repository Understanding" :difficulty "easy"}
               {:_id "2" :domain "Single-Document QA" :difficulty "easy"}
               {:_id "3" :domain "Code Repository Understanding" :difficulty "hard"}
               {:_id "4" :domain "Multi-Document QA" :difficulty "easy"}]
        result (lb/filter-code-repo-questions items)]
    (is (= 2 (count result)))
    (is (= ["1" "3"] (mapv :_id result)))))

(deftest filter-code-repo-questions-empty-input
  (is (= [] (lb/filter-code-repo-questions []))))

(deftest filter-code-repo-questions-no-matches
  (let [items [{:_id "1" :domain "Single-Document QA"}
               {:_id "2" :domain "Multi-Document QA"}]]
    (is (= [] (lb/filter-code-repo-questions items)))))

(deftest filter-code-repo-questions-preserves-all-fields
  (let [item {:_id "1"
              :domain "Code Repository Understanding"
              :sub_domain "Python"
              :difficulty "hard"
              :length "long"
              :question "What does foo do?"
              :choice_A "Option A"
              :choice_B "Option B"
              :choice_C "Option C"
              :choice_D "Option D"
              :answer "B"
              :context "def foo(): pass"}
        result (lb/filter-code-repo-questions [item])]
    (is (= 1 (count result)))
    (is (= item (first result)))))

;; --- build-prompt ---

(deftest build-prompt-substitutes-all-placeholders
  (let [q {:context "some code here"
           :question "What does this do?"
           :choice_A "First option"
           :choice_B "Second option"
           :choice_C "Third option"
           :choice_D "Fourth option"}
        prompt (lb/build-prompt q)]
    (is (not (re-find #"\$DOC\$" prompt)))
    (is (not (re-find #"\$Q\$" prompt)))
    (is (not (re-find #"\$C_[A-D]\$" prompt)))
    (is (re-find #"some code here" prompt))
    (is (re-find #"What does this do\?" prompt))
    (is (re-find #"First option" prompt))
    (is (re-find #"Second option" prompt))
    (is (re-find #"Third option" prompt))
    (is (re-find #"Fourth option" prompt))))

(deftest build-prompt-trims-whitespace
  (let [q {:context "  code  " :question "  q  "
           :choice_A " A " :choice_B " B " :choice_C " C " :choice_D " D "}
        prompt (lb/build-prompt q)]
    (is (re-find #"code" prompt))
    (is (not (re-find #"  code  " prompt)))))

;; --- extract-answer ---

(deftest extract-answer-with-parens
  (is (= "B" (lb/extract-answer "The correct answer is (B)"))))

(deftest extract-answer-without-parens
  (is (= "C" (lb/extract-answer "The correct answer is C"))))

(deftest extract-answer-prefers-parens
  (is (= "A" (lb/extract-answer "The correct answer is (A) because of X. The correct answer is B"))))

(deftest extract-answer-strips-asterisks
  (is (= "D" (lb/extract-answer "**The correct answer is (D)**"))))

(deftest extract-answer-no-match
  (is (nil? (lb/extract-answer "I think the answer might be B but I'm not sure"))))

(deftest extract-answer-nil-input
  (is (nil? (lb/extract-answer nil))))

(deftest extract-answer-empty-string
  (is (nil? (lb/extract-answer ""))))

(deftest extract-answer-invalid-letter
  (is (nil? (lb/extract-answer "The correct answer is (E)"))))

;; --- score-question ---

(deftest score-question-correct
  (is (= 1.0 (lb/score-question "B" "B"))))

(deftest score-question-incorrect
  (is (= 0.0 (lb/score-question "A" "B"))))

(deftest score-question-nil-prediction
  (is (= 0.0 (lb/score-question nil "B"))))

;; --- truncate-middle ---

(deftest truncate-middle-no-op-under-limit
  (let [s "short text"]
    (is (= s (lb/truncate-middle s 100)))))

(deftest truncate-middle-splits-correctly
  (let [s (apply str (repeat 100 "abcd"))  ;; 400 chars
        ;; max-tokens=50 → max-chars=200 → keep first 100 + last 100
        result (lb/truncate-middle s 50)]
    (is (= 200 (count result)))
    (is (= (subs s 0 100) (subs result 0 100)))
    (is (= (subs s 300 400) (subs result 100 200)))))

(deftest truncate-middle-exact-limit
  (let [s (apply str (repeat 100 "a"))]  ;; 100 chars
    ;; 25 tokens * 4 = 100 chars, exactly at limit
    (is (= s (lb/truncate-middle s 25)))))

(deftest truncate-middle-default-200k
  (let [s (apply str (repeat 100 "a"))]  ;; 100 chars, well under 200K tokens
    (is (= s (lb/truncate-middle s)))))

;; --- aggregate-results ---

(deftest aggregate-results-all-correct
  (let [results [{:prediction "A" :answer "A" :difficulty "easy" :length "short"}
                 {:prediction "B" :answer "B" :difficulty "hard" :length "long"}]
        agg (lb/aggregate-results results)]
    (is (= 100.0 (:overall agg)))
    (is (= 2 (:total agg)))))

(deftest aggregate-results-mixed
  (let [results [{:prediction "A" :answer "A" :difficulty "easy" :length "short"}
                 {:prediction "A" :answer "B" :difficulty "easy" :length "short"}
                 {:prediction "C" :answer "C" :difficulty "hard" :length "long"}
                 {:prediction nil :answer "D" :difficulty "hard" :length "medium"}]
        agg (lb/aggregate-results results)]
    (is (= 50.0 (:overall agg)))
    (is (= 50.0 (:easy agg)))
    (is (= 50.0 (:hard agg)))
    (is (= 50.0 (:short agg)))
    (is (= 0.0 (:medium agg)))
    (is (= 100.0 (:long agg)))))

(deftest aggregate-results-by-difficulty
  (let [results [{:prediction "A" :answer "A" :difficulty "easy" :length "short"}
                 {:prediction "B" :answer "C" :difficulty "hard" :length "long"}]
        agg (lb/aggregate-results results)]
    (is (= 100.0 (:easy agg)))
    (is (= 0.0 (:hard agg)))))

(deftest aggregate-results-nil-for-missing-category
  (let [results [{:prediction "A" :answer "A" :difficulty "easy" :length "short"}]
        agg (lb/aggregate-results results)]
    (is (nil? (:hard agg)))
    (is (nil? (:medium agg)))
    (is (nil? (:long agg)))))

(deftest aggregate-results-empty
  (let [agg (lb/aggregate-results [])]
    (is (nil? (:overall agg)))
    (is (= 0 (:total agg)))))

;; --- Tier 1: Integration (mock LLM) ---

(deftest run-longbench-with-mock-llm
  (testing "run-longbench! with max-questions budget"
    (let [call-count (atom 0)
          mock-llm   (fn [_prompt]
                       (swap! call-count inc)
                       {:text  "The correct answer is (A)"
                        :usage {:input-tokens 100 :output-tokens 10
                                :cost-usd 0.001 :duration-ms 50}
                        :model "mock"})
          ;; Mock load-code-repo-questions to return 3 test questions
          test-qs    [{:_id "q1" :domain "Code Repository Understanding"
                       :difficulty "easy" :length "short"
                       :question "Q1?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "A"
                       :context "code1"}
                      {:_id "q2" :domain "Code Repository Understanding"
                       :difficulty "hard" :length "long"
                       :question "Q2?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "B"
                       :context "code2"}
                      {:_id "q3" :domain "Code Repository Understanding"
                       :difficulty "easy" :length "medium"
                       :question "Q3?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "A"
                       :context "code3"}]
          tmp-dir    (str (System/getProperty "java.io.tmpdir")
                          "/longbench-test-" (System/currentTimeMillis))]
      (try
        (with-redefs [lb/load-code-repo-questions (constantly test-qs)]
          (let [result (lb/run-longbench! mock-llm
                                          :checkpoint-dir tmp-dir
                                          :budget {:max-questions 2}
                                          :concurrency 1)]
            ;; Should have called LLM exactly 2 times (budget limit)
            (is (<= (count (:results result)) 2))
            (is (some? (:aggregate result)))
            (is (some? (:run-id result)))))
        (finally
          ;; Cleanup
          (doseq [f (reverse (file-seq (java.io.File. tmp-dir)))]
            (.delete f)))))))

(deftest run-longbench-resume-skips-completed
  (testing "resume skips already-completed questions, runs only remaining"
    (let [call-count (atom 0)
          mock-llm   (fn [_msgs]
                       (swap! call-count inc)
                       {:text  "The correct answer is (B)"
                        :usage {:input-tokens 100 :output-tokens 10
                                :cost-usd 0.001 :duration-ms 50}
                        :model "mock"})
          test-qs    [{:_id "q1" :domain "Code Repository Understanding"
                       :difficulty "easy" :length "short"
                       :question "Q1?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "B"
                       :context "code1"}
                      {:_id "q2" :domain "Code Repository Understanding"
                       :difficulty "hard" :length "long"
                       :question "Q2?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "A"
                       :context "code2"}
                      {:_id "q3" :domain "Code Repository Understanding"
                       :difficulty "easy" :length "medium"
                       :question "Q3?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "B"
                       :context "code3"}]
          tmp-dir    (str (System/getProperty "java.io.tmpdir")
                          "/longbench-resume-test-" (System/currentTimeMillis))
          checkpoint {:run-id   (bench/generate-run-id)
                      :metadata {:started-at (java.util.Date.)
                                 :budget     {}}
                      :results  {"q1" {:prediction "B" :correct true
                                       :usage {:input-tokens 100 :output-tokens 10
                                               :cost-usd 0.001 :duration-ms 50}}}}]
      (try
        (with-redefs [lb/load-code-repo-questions (constantly test-qs)]
          (let [result (lb/run-longbench-resume! mock-llm checkpoint
                                                 :checkpoint-dir tmp-dir
                                                 :concurrency 1)]
            (testing "LLM called only for remaining questions (q2, q3)"
              (is (= 2 @call-count)))
            (testing "all 3 questions in final results"
              (is (= 3 (count (:results result)))))
            (testing "aggregate covers all completed"
              (is (= 3 (:total (:aggregate result)))))))
        (finally
          (doseq [f (reverse (file-seq (java.io.File. tmp-dir)))]
            (.delete f)))))))
