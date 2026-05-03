(ns noumenon.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.cli :as cli]))

;; --- Error envelopes carry :subcommand uniformly ---
;;
;; Callers of cli/parse-args (main/handle-parse-error) need :subcommand on
;; every error return so they can route contextual help. Without it, errors
;; from `benchmark` and `ask` printed only the generic global help.

(deftest parse-args-benchmark-missing-repo-includes-subcommand
  (let [result (cli/parse-args ["benchmark"])]
    (is (= :no-repo-path (:error result)))
    (is (= "benchmark" (:subcommand result))
        (str "benchmark error returns must name the subcommand; got: "
             (pr-str result)))))

(deftest parse-args-benchmark-resume-with-no-repo-path-includes-subcommand
  (testing "When --resume is given followed by a flag-like value (and no
            trailing repo-path), the error envelope still names the
            subcommand so the caller can print benchmark-specific help."
    (let [result (cli/parse-args ["benchmark" "--resume" "/tmp/foo"])]
      (is (some? (:error result)))
      (is (= "benchmark" (:subcommand result))
          (str "benchmark error returns must name the subcommand; got: "
               (pr-str result))))))

(deftest parse-args-ask-missing-question-includes-subcommand
  (let [result (cli/parse-args ["ask" "/tmp/x"])]
    (is (= :ask-missing-question (:error result)))
    (is (= "ask" (:subcommand result))
        (str "ask error returns must name the subcommand; got: "
             (pr-str result)))))

(deftest parse-args-ask-no-repo-includes-subcommand
  (let [result (cli/parse-args ["ask" "-q" "what?"])]
    (is (= :no-repo-path (:error result)))
    (is (= "ask" (:subcommand result))
        (str "ask error returns must name the subcommand; got: "
             (pr-str result)))))

;; --- Other subcommand errors already carried :subcommand pre-fix; assert
;; that the contract holds across a representative spread.

(deftest parse-args-digest-missing-repo-includes-subcommand
  (let [result (cli/parse-args ["digest"])]
    (is (= :no-repo-path (:error result)))
    (is (= "digest" (:subcommand result)))))

(deftest parse-args-status-missing-repo-includes-subcommand
  (let [result (cli/parse-args ["status"])]
    (is (= :no-repo-path (:error result)))
    (is (= "status" (:subcommand result)))))
