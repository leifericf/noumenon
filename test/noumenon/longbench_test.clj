(ns noumenon.longbench-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.cli :as cli]
            [noumenon.llm]
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

(deftest build-prompt-no-template-smuggling
  (testing "context containing $Q$ does not substitute the question placeholder"
    (let [q {:context "code with $Q$ literal"
             :question "What is this?"
             :choice_A "A" :choice_B "B" :choice_C "C" :choice_D "D"}
          prompt (lb/build-prompt q)]
      (is (re-find #"code with \$Q\$ literal" prompt)
          "context $Q$ should remain literal, not be replaced by question")
      (is (re-find #"What is this\?" prompt)
          "question should appear in its correct placeholder location"))))

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

;; --- experiment config ---

(def ^:private valid-config
  {:experiment/name "test-experiment"
   :arms [{:arm/name "pure-llm" :arm/type :pure-llm}]
   :model {:provider "glm" :alias "sonnet"}})

(deftest validate-experiment-config-valid
  (is (= {:ok valid-config}
         (lb/validate-experiment-config valid-config))))

(deftest validate-experiment-config-missing-name
  (is (:error (lb/validate-experiment-config (dissoc valid-config :experiment/name)))))

(deftest validate-experiment-config-missing-arms
  (is (:error (lb/validate-experiment-config (dissoc valid-config :arms)))))

(deftest validate-experiment-config-empty-arms
  (is (:error (lb/validate-experiment-config (assoc valid-config :arms [])))))

(deftest validate-experiment-config-arm-missing-name
  (is (:error (lb/validate-experiment-config
               (assoc valid-config :arms [{:arm/type :pure-llm}])))))

(deftest validate-experiment-config-arm-missing-type
  (is (:error (lb/validate-experiment-config
               (assoc valid-config :arms [{:arm/name "x"}])))))

(deftest validate-experiment-config-missing-provider
  (is (:error (lb/validate-experiment-config
               (assoc valid-config :model {:alias "sonnet"})))))

(deftest validate-experiment-config-missing-alias
  (is (:error (lb/validate-experiment-config
               (assoc valid-config :model {:provider "glm"})))))

(deftest config-hash-deterministic
  (is (= (lb/config-hash valid-config)
         (lb/config-hash valid-config))))

(deftest config-hash-ignores-output-dir
  (is (= (lb/config-hash valid-config)
         (lb/config-hash (assoc valid-config :output/dir "/tmp/other")))))

(deftest config-hash-differs-for-different-config
  (is (not= (lb/config-hash valid-config)
            (lb/config-hash (assoc valid-config :experiment/name "other")))))

(deftest resolve-config-defaults-fills-gaps
  (let [resolved (lb/resolve-config-defaults valid-config)]
    (is (= "data/longbench/data.json" (:dataset/path resolved)))
    (is (= "Code Repository Understanding" (:dataset/domain resolved)))
    (is (= 3 (get-in resolved [:execution :concurrency])))
    (is (= 0 (get-in resolved [:execution :min-delay-ms])))
    (is (= 1 (get-in resolved [:execution :repeats])))
    (is (string? (:output/dir resolved)))))

(deftest resolve-config-defaults-preserves-overrides
  (let [config   (assoc valid-config
                        :execution {:concurrency 10 :min-delay-ms 500}
                        :output/dir "/custom/")
        resolved (lb/resolve-config-defaults config)]
    (is (= 10 (get-in resolved [:execution :concurrency])))
    (is (= 500 (get-in resolved [:execution :min-delay-ms])))
    (is (= 1 (get-in resolved [:execution :repeats])))
    (is (= "/custom/" (:output/dir resolved)))))

;; --- arm runner ---

(def ^:private test-question
  {:_id "q1" :domain "Code Repository Understanding"
   :difficulty "easy" :length "short"
   :question "What does foo do?"
   :choice_A "A" :choice_B "B" :choice_C "C" :choice_D "D"
   :answer "B" :context "def foo(): pass"})

(deftest run-pure-llm-arm-correct-answer
  (let [mock-llm (fn [_msgs]
                   {:text  "The correct answer is (B)"
                    :usage {:input-tokens 100 :output-tokens 10
                            :cost-usd 0.001 :duration-ms 50}
                    :model "mock"})
        result   (lb/run-pure-llm-arm {:question test-question
                                       :invoke-llm mock-llm})]
    (is (= "B" (:prediction result)))
    (is (string? (:response result)))
    (is (map? (:usage result)))
    (is (nat-int? (:timing-ms result)))))

(deftest run-pure-llm-arm-nil-on-bad-response
  (let [mock-llm (fn [_msgs]
                   {:text "I'm not sure about this"
                    :usage {:input-tokens 100 :output-tokens 10
                            :cost-usd 0.001 :duration-ms 50}})
        result   (lb/run-pure-llm-arm {:question test-question
                                       :invoke-llm mock-llm})]
    (is (nil? (:prediction result)))))

(deftest run-pure-llm-arm-truncates-long-context
  (let [long-ctx (apply str (repeat 250000 "abcd"))  ;; 1M chars > 200K tokens
        question (assoc test-question :context long-ctx)
        prompts  (atom [])
        mock-llm (fn [msgs]
                   (swap! prompts conj (get-in msgs [0 :content]))
                   {:text  "The correct answer is (A)"
                    :usage {:input-tokens 100 :output-tokens 10
                            :cost-usd 0.001 :duration-ms 50}})
        _result  (lb/run-pure-llm-arm {:question question :invoke-llm mock-llm})]
    (is (< (count (first @prompts)) (count long-ctx)))))

(deftest resolve-arm-runner-pure-llm
  (is (= lb/run-pure-llm-arm (lb/resolve-arm-runner :pure-llm))))

(deftest resolve-arm-runner-unknown-throws
  (is (thrown? clojure.lang.ExceptionInfo (lb/resolve-arm-runner :unknown))))

;; --- Tier 1: Integration (mock LLM) ---

;; --- Experiment orchestrator ---

(deftest run-experiment-with-mock-llm
  (testing "run-experiment! produces arm results, JSONL, checkpoint, and report"
    (let [call-count (atom 0)
          mock-llm   (fn [_msgs]
                       (swap! call-count inc)
                       {:text  "The correct answer is (A)"
                        :usage {:input-tokens 100 :output-tokens 10
                                :cost-usd 0.001 :duration-ms 50}
                        :model "mock"})
          test-qs    [{:_id "q1" :domain "Code Repository Understanding"
                       :difficulty "easy" :length "short"
                       :question "Q1?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "A"
                       :context "code1"}
                      {:_id "q2" :domain "Code Repository Understanding"
                       :difficulty "hard" :length "long"
                       :question "Q2?" :choice_A "A" :choice_B "B"
                       :choice_C "C" :choice_D "D" :answer "B"
                       :context "code2"}]
          tmp-dir    (str (System/getProperty "java.io.tmpdir")
                          "/longbench-experiment-" (System/currentTimeMillis))
          config     {:experiment/name "test-exp"
                      :arms [{:arm/name "pure-llm" :arm/type :pure-llm}]
                      :model {:provider "glm" :alias "sonnet"}
                      :budgets {:max-questions 2}
                      :execution {:concurrency 1 :min-delay-ms 0 :repeats 1}
                      :output/dir (str tmp-dir "/out/")}]
      (try
        (with-redefs [lb/load-code-repo-questions    (constantly test-qs)
                      lb/ensure-dataset!             (constantly "data/longbench/data.json")
                      lb/build-experiment-metadata   (fn [config cfg-hash _ model-cfg]
                                                       {:git-sha "test" :config-hash cfg-hash
                                                        :dataset-hash "test" :model model-cfg
                                                        :started-at (java.util.Date.)
                                                        :experiment (:experiment/name config)})
                      noumenon.llm/make-invoke-fn     (constantly mock-llm)]
          (let [summary (lb/run-experiment! config)]
            (testing "returns arm results"
              (is (= 1 (count (:arm-results summary))))
              (is (= "pure-llm" (:arm-name (first (:arm-results summary))))))
            (testing "aggregate present"
              (let [agg (:aggregate (first (:arm-results summary)))]
                (is (some? (:overall agg)))
                (is (= 2 (:total agg)))))
            (testing "config hash recorded"
              (is (string? (:config-hash summary))))
            (testing "metadata present"
              (is (= "test-exp" (get-in summary [:metadata :experiment]))))
            (testing "summary file written"
              (is (.exists (java.io.File.
                            (str tmp-dir "/out/test-exp/summary.edn")))))))
        (finally
          (doseq [f (reverse (file-seq (java.io.File. tmp-dir)))]
            (.delete f)))))))

(deftest generate-experiment-report-contains-sections
  (let [summary {:metadata    {:experiment "test"
                               :git-sha "abc123"
                               :config-hash "def456"
                               :dataset-hash "ghi789012345678"
                               :model {:model-id "claude-sonnet" :provider-kw :glm}
                               :started-at (java.util.Date.)}
                 :arm-results [{:arm-name "pure-llm"
                                :aggregate {:overall 50.0 :easy 100.0 :hard 0.0
                                            :short 50.0 :medium nil :long nil
                                            :total 2}
                                :total-usage {:cost-usd 0.002}}]}
        report  (lb/generate-experiment-report summary)]
    (is (re-find #"test" report))
    (is (re-find #"abc123" report))
    (is (re-find #"pure-llm" report))
    (is (re-find #"50\.0%" report))
    (is (re-find #"Overall" report))))

;; --- CLI parsing ---

(deftest parse-longbench-experiment-with-config
  (let [result (cli/parse-longbench-args ["experiment" "--config" "path/to/config.edn"])]
    (is (= "longbench" (:subcommand result)))
    (is (= "experiment" (:longbench-command result)))
    (is (= "path/to/config.edn" (:config result)))))

(deftest parse-longbench-experiment-missing-config
  (let [result (cli/parse-longbench-args ["experiment"])]
    (is (= :missing-config-value (:error result)))))

(deftest parse-longbench-download-unchanged
  (let [result (cli/parse-longbench-args ["download"])]
    (is (= "longbench" (:subcommand result)))
    (is (= "download" (:longbench-command result)))))

(deftest parse-longbench-run-rejected
  (let [result (cli/parse-longbench-args ["run"])]
    (is (= :longbench-unknown-subcommand (:error result)))))

(deftest parse-longbench-no-subcommand
  (let [result (cli/parse-longbench-args [])]
    (is (= :longbench-no-subcommand (:error result)))))

(deftest parse-longbench-help
  (let [result (cli/parse-longbench-args ["--help"])]
    (is (= "longbench" (:help result)))))
