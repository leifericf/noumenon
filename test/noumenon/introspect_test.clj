(ns noumenon.introspect-test
  (:require [clojure.test :refer [deftest is testing]]
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

;; --- History formatting ---

(deftest format-history-empty
  (let [prompt (intro/build-meta-prompt
                {:system-prompt "test" :examples ["a"] :history [] :baseline-results []})]
    (is (string? prompt))
    (is (.contains prompt "No prior iterations."))))

;; --- Score calculation ---

(deftest score-kw-to-num
  (is (= 1.0 (#'intro/score-kw->num :correct)))
  (is (= 0.5 (#'intro/score-kw->num :partial)))
  (is (= 0.0 (#'intro/score-kw->num :wrong))))
