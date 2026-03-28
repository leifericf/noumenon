(ns noumenon.introspect-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [noumenon.introspect :as intro]
            [noumenon.test-helpers :as th]))

;; --- Proposal parsing ---

(deftest parse-proposal-valid
  (is (= {:target :examples
          :modification {:examples ["recent-commits" "top-contributors"]}
          :rationale "Test rationale"}
         (intro/parse-proposal
          "{:target :examples :modification {:examples [\"recent-commits\" \"top-contributors\"]} :rationale \"Test rationale\"}"))))

(deftest parse-proposal-with-markdown-fences
  (is (map? (intro/parse-proposal
             "```edn\n{:target :examples :modification {:examples [\"recent-commits\"]} :rationale \"test\"}\n```"))))

(deftest parse-proposal-invalid-edn
  (is (nil? (intro/parse-proposal "not valid edn {{"))))

(deftest parse-proposal-nil-text
  (is (nil? (intro/parse-proposal nil))))

(deftest parse-proposal-empty-string
  (is (nil? (intro/parse-proposal ""))))

(deftest parse-proposal-non-map
  (is (nil? (intro/parse-proposal "[1 2 3]"))))

;; --- Proposal validation ---

(deftest validate-proposal-invalid-target
  (is (string? (intro/validate-proposal
                {:target :invalid :modification {} :rationale "test"}))))

(deftest validate-proposal-missing-rationale
  (is (string? (intro/validate-proposal
                {:target :examples :modification {:examples ["recent-commits"]} :rationale nil}))))

(deftest validate-proposal-system-prompt-missing-placeholders
  (is (string? (intro/validate-proposal
                {:target :system-prompt
                 :modification {:template "No placeholders here"}
                 :rationale "test"}))))

(deftest validate-proposal-system-prompt-valid
  (is (nil? (intro/validate-proposal
             {:target :system-prompt
              :modification {:template "Prompt for {{repo-name}} with {{schema}} and {{rules}} plus {{examples}}"}
              :rationale "test"}))))

(deftest validate-proposal-rules-valid
  (is (nil? (intro/validate-proposal
             {:target :rules
              :modification {:rules "[[(my-rule ?x ?y) [?x :file/path ?y]]]"}
              :rationale "test"}))))

(deftest validate-proposal-rules-invalid-edn
  (is (string? (intro/validate-proposal
                {:target :rules
                 :modification {:rules "not valid edn {{"}
                 :rationale "test"}))))

(deftest validate-proposal-rules-non-vector
  (is (string? (intro/validate-proposal
                {:target :rules
                 :modification {:rules "{:not :a-vector}"}
                 :rationale "test"}))))

(deftest validate-proposal-code-valid
  (is (nil? (intro/validate-proposal
             {:target :code
              :modification {:file "src/noumenon/query.clj"
                             :content "(ns noumenon.query)"}
              :rationale "test"}))))

(deftest validate-proposal-code-wrong-dir
  (is (string? (intro/validate-proposal
                {:target :code
                 :modification {:file "test/foo.clj" :content "x"}
                 :rationale "test"}))))

(deftest validate-proposal-code-not-clj
  (is (string? (intro/validate-proposal
                {:target :code
                 :modification {:file "src/noumenon/foo.py" :content "x"}
                 :rationale "test"}))))

;; --- Security: path traversal in :code target ---

(deftest validate-proposal-code-path-traversal
  (is (string? (intro/validate-proposal
                {:target :code
                 :modification {:file "src/noumenon/../../.env" :content "x"}
                 :rationale "test"})))
  (is (string? (intro/validate-proposal
                {:target :code
                 :modification {:file "src/noumenon/../../etc/passwd.clj" :content "x"}
                 :rationale "test"})))
  (is (string? (intro/validate-proposal
                {:target :code
                 :modification {:file "src/noumenon/../../../tmp/evil.clj" :content "x"}
                 :rationale "test"}))))

(deftest validate-proposal-code-nil-file
  (is (string? (intro/validate-proposal
                {:target :code
                 :modification {:file nil :content "x"}
                 :rationale "test"}))))

(deftest validate-proposal-code-nil-content
  (is (string? (intro/validate-proposal
                {:target :code
                 :modification {:file "src/noumenon/foo.clj" :content nil}
                 :rationale "test"}))))

(deftest validate-proposal-train-valid
  (is (nil? (intro/validate-proposal
             {:target :train
              :modification {:config {:learning-rate 0.002}}
              :rationale "test"}))))

(deftest validate-proposal-train-invalid
  (is (string? (intro/validate-proposal
                {:target :train
                 :modification {:config "not a map"}
                 :rationale "test"}))))

(deftest validate-proposal-nil-target
  (is (string? (intro/validate-proposal
                {:target nil :modification {} :rationale "test"}))))

(deftest validate-proposal-empty-examples
  (is (nil? (intro/validate-proposal
             {:target :examples
              :modification {:examples []}
              :rationale "test"}))))

;; --- Gap analysis ---

(deftest gap-analysis-empty
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history [] :baseline-results []})]
    (is (string? prompt))
    (is (.contains prompt "No baseline data"))))

(deftest gap-analysis-with-results
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history []
                 :baseline-results [{:id :q01 :score :correct :reasoning "ok"}
                                    {:id :q02 :score :wrong :reasoning "missed"}]})]
    (is (.contains prompt "WRONG answers"))
    (is (.contains prompt "q02"))))

(deftest gap-analysis-all-correct
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history []
                 :baseline-results [{:id :q01 :score :correct :reasoning "ok"}
                                    {:id :q02 :score :correct :reasoning "ok"}]})]
    (is (not (.contains prompt "WRONG")))
    (is (not (.contains prompt "PARTIAL")))))

(deftest gap-analysis-nil-reasoning
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history []
                 :baseline-results [{:id :q01 :score :wrong :reasoning nil}]})]
    (is (string? prompt))
    (is (.contains prompt "WRONG"))))

;; --- History formatting ---

(deftest format-history-with-goals
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history [{:target :examples :rationale "test" :outcome :improved
                            :delta 0.05 :goal "improve accuracy"}]
                 :baseline-results []})]
    (is (.contains prompt "goal=\"improve accuracy\""))))

(deftest format-history-with-skipped-records
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history [{:outcome :skipped :rationale "Parse failure"}]
                 :baseline-results []})]
    (is (string? prompt))
    (is (.contains prompt "skipped"))))

(deftest format-history-nil-fields
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history [{:target nil :outcome nil :rationale nil :delta nil :goal nil}]
                 :baseline-results []})]
    (is (string? prompt))
    (is (.contains prompt "unknown"))))

;; --- Meta-prompt template completeness ---

(deftest meta-prompt-no-unfilled-placeholders
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test prompt" :examples ["recent-commits"] :rules []
                 :history [] :baseline-results []})
        own-placeholders ["{{current-system-prompt}}" "{{current-examples}}"
                          "{{example-count}}" "{{total-queries}}"
                          "{{all-queries}}" "{{current-rules}}"
                          "{{baseline-mean}}" "{{baseline-scores}}"
                          "{{gap-analysis}}" "{{history}}"]]
    (doseq [p own-placeholders]
      (is (not (.contains prompt p))
          (str "Unfilled placeholder: " p)))))

(deftest meta-prompt-contains-system-prompt
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "UNIQUE_MARKER_XYZ" :examples [] :rules []
                 :history [] :baseline-results []})]
    (is (.contains prompt "UNIQUE_MARKER_XYZ"))))

;; --- Datomic history: load-history from meta DB ---

(deftest load-history-empty-db
  (let [conn (th/make-test-conn "introspect-empty")]
    (is (= [] (intro/load-history (d/db conn))))))

(deftest load-history-with-data
  (let [conn (th/make-test-conn "introspect-history")]
    (d/transact conn {:tx-data
                      [{:introspect.run/id "test-run-1"
                        :introspect.run/repo-path "/test"
                        :introspect.run/started-at (java.util.Date.)
                        :introspect.run/completed-at (java.util.Date.)
                        :introspect.run/iteration-count 2
                        :introspect.run/improvement-count 1
                        :introspect.run/iterations
                        [{:introspect.iter/index 1
                          :introspect.iter/target :examples
                          :introspect.iter/outcome :improved
                          :introspect.iter/rationale "Added better examples"
                          :introspect.iter/delta 0.05
                          :introspect.iter/goal "improve accuracy"
                          :introspect.iter/timestamp (java.util.Date.)}
                         {:introspect.iter/index 2
                          :introspect.iter/target :system-prompt
                          :introspect.iter/outcome :reverted
                          :introspect.iter/rationale "Tried new instructions"
                          :introspect.iter/delta -0.02
                          :introspect.iter/goal "reduce hallucination"
                          :introspect.iter/timestamp (java.util.Date.)}]}
                       {:db/id "datomic.tx" :tx/op :introspect}]})
    (let [history (intro/load-history (d/db conn))]
      (is (= 2 (count history)))
      (is (= :examples (:target (first history))))
      (is (= :improved (:outcome (first history))))
      (is (= :reverted (:outcome (second history)))))))

;; --- Datomic persistence: run->tx-data ---

(deftest run-tx-data-round-trip
  (let [conn    (th/make-test-conn "introspect-txdata")
        tx-data (intro/run->tx-data
                 {:run-id            "test-run-42"
                  :repo-path         "/test/repo"
                  :commit-sha        "abc123"
                  :started-at        (java.util.Date.)
                  :model-config      {:provider "glm" :model "sonnet"}
                  :max-iterations    10
                  :prompt-hash       "hash1"
                  :examples-hash     "hash2"
                  :rules-hash        "hash3"
                  :db-basis-t        100
                  :baseline-mean     0.5
                  :final-mean        0.6
                  :iteration-count   2
                  :improvement-count 1
                  :cost-usd          1.23
                  :iter-records      [{:target :examples :outcome :improved
                                       :rationale "test" :goal "test goal"
                                       :baseline 0.5 :result 0.6 :delta 0.1
                                       :modification {:examples ["a" "b"]}}
                                      {:target :system-prompt :outcome :reverted
                                       :rationale "test2" :goal "test goal 2"
                                       :baseline 0.6 :result 0.55 :delta -0.05}]})]
    ;; Transact should succeed
    (d/transact conn {:tx-data tx-data})
    ;; Query back the run
    (let [db  (d/db conn)
          run (d/pull db '[*] [:introspect.run/id "test-run-42"])]
      (is (= "test-run-42" (:introspect.run/id run)))
      (is (= "/test/repo" (:introspect.run/repo-path run)))
      (is (= 0.5 (:introspect.run/baseline-mean run)))
      (is (= 0.6 (:introspect.run/final-mean run)))
      (is (= 2 (count (:introspect.run/iterations run)))))
    ;; Verify history loads from the new data
    (let [history (intro/load-history (d/db conn))]
      (is (= 2 (count history)))
      (is (= :improved (:outcome (first history)))))))

;; --- Code verification ---

(deftest verify-code-syntax-valid
  (is (nil? (#'intro/verify-code-syntax "(ns foo.bar)\n(defn x [] 1)"))))

(deftest verify-code-syntax-invalid
  (is (string? (#'intro/verify-code-syntax "(defn x [)"))))

(deftest verify-code-syntax-empty
  (is (nil? (#'intro/verify-code-syntax ""))))

;; --- Multimethod dispatch ---

(deftest apply-modification-examples-round-trip
  ;; apply-modification! returns the original, which can be used to revert
  (let [orig-content (slurp (#'intro/resource-path "prompts/agent-examples.edn"))
        proposal     {:target :examples
                      :modification {:examples ["recent-commits"]}}
        saved-orig   (intro/apply-modification! proposal)]
    ;; File was modified
    (is (not= orig-content (slurp (#'intro/resource-path "prompts/agent-examples.edn"))))
    ;; Revert restores exact original
    (intro/revert-modification! proposal saved-orig)
    (is (= orig-content (slurp (#'intro/resource-path "prompts/agent-examples.edn"))))))

;; --- Score calculation ---

(deftest score-kw-to-num
  (is (= 1.0 (#'intro/score-kw->num :correct)))
  (is (= 0.5 (#'intro/score-kw->num :partial)))
  (is (= 0.0 (#'intro/score-kw->num :wrong))))
