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

;; --- History formatting ---

(deftest format-history-with-goals
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history [{:target :examples :rationale "test" :outcome :improved
                            :delta 0.05 :goal "improve accuracy"}]
                 :baseline-results []})]
    (is (.contains prompt "goal=\"improve accuracy\""))))

(deftest format-history-with-skipped-records
  ;; Skipped records have no :target — must not NPE
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :rules []
                 :history [{:outcome :skipped :rationale "Parse failure"}]
                 :baseline-results []})]
    (is (string? prompt))
    (is (.contains prompt "skipped"))))

;; --- Load history ---

(deftest load-history-missing-file
  (is (= [] (intro/load-history "/tmp/nonexistent-introspect-history.edn"))))

(deftest load-history-corrupted-file
  (let [path (str "/tmp/introspect-test-corrupted-" (System/currentTimeMillis) ".edn")]
    (spit path "not valid edn {{")
    (try
      ;; Should not crash the process
      (is (= [] (intro/load-history path)))
      (finally
        (.delete (java.io.File. path))))))

;; --- Score calculation ---

(deftest score-kw-to-num
  (is (= 1.0 (#'intro/score-kw->num :correct)))
  (is (= 0.5 (#'intro/score-kw->num :partial)))
  (is (= 0.0 (#'intro/score-kw->num :wrong))))
