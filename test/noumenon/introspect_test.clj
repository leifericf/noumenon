(ns noumenon.introspect-test
  (:require [clojure.test :refer [deftest is]]
            [noumenon.introspect :as intro]))

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
  ;; Check that all introspect template placeholders are filled.
  ;; The template intentionally contains {{repo-name}} etc. as documentation
  ;; for the LLM about the agent system prompt — those are NOT unfilled.
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test prompt" :examples ["recent-commits"] :rules []
                 :history [] :baseline-results []})
        ;; These are the introspect template's own placeholders
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

;; --- Load history ---

(deftest load-history-missing-file
  (is (= [] (intro/load-history "/tmp/nonexistent-introspect-history.edn"))))

(deftest load-history-corrupted-file
  (let [path (str "/tmp/introspect-test-corrupted-" (System/currentTimeMillis) ".edn")]
    (spit path "not valid edn {{")
    (try
      (is (= [] (intro/load-history path)))
      (finally
        (.delete (java.io.File. path))))))

(deftest load-history-non-vector-data
  (let [path (str "/tmp/introspect-test-nonvec-" (System/currentTimeMillis) ".edn")]
    (spit path "{:not :a-vector}")
    (try
      (is (= [] (intro/load-history path)))
      (finally
        (.delete (java.io.File. path))))))

(deftest load-history-valid
  (let [path (str "/tmp/introspect-test-valid-" (System/currentTimeMillis) ".edn")]
    (spit path "[{:outcome :improved}]")
    (try
      (is (= [{:outcome :improved}] (intro/load-history path)))
      (finally
        (.delete (java.io.File. path))))))

;; --- Append history round-trip ---

(deftest append-history-creates-file
  (let [path (str "/tmp/introspect-test-append-" (System/currentTimeMillis) ".edn")]
    (try
      (intro/append-history! path {:outcome :improved :target :examples})
      (is (= [{:outcome :improved :target :examples}]
             (intro/load-history path)))
      (intro/append-history! path {:outcome :reverted :target :rules})
      (is (= 2 (count (intro/load-history path))))
      (finally
        (.delete (java.io.File. path))))))

;; --- Score calculation ---

(deftest score-kw-to-num
  (is (= 1.0 (#'intro/score-kw->num :correct)))
  (is (= 0.5 (#'intro/score-kw->num :partial)))
  (is (= 0.0 (#'intro/score-kw->num :wrong))))
