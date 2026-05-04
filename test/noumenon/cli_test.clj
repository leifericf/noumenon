(ns noumenon.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.agent :as agent]
            [noumenon.cli :as cli]
            [noumenon.cli.commands.ask :as c-ask]
            [noumenon.cli.util :as cu]
            [noumenon.embed :as embed]
            [noumenon.llm :as llm]))

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

(deftest do-ask-caps-max-iterations-at-50
  (testing "CLI do-ask must clamp --max-iterations to the same upper
            bound the HTTP /api/ask handler enforces (50). Without the
            cap a CLI caller could pass --max-iterations 10000 and
            spend through any --max-cost / --stop-after budget the
            agent loop would otherwise have respected."
    (let [seen (atom nil)]
      (with-redefs [cu/with-valid-repo  (fn [_opts run!] (run! {:db-dir "/tmp"}))
                    cu/with-existing-db (fn [ctx run!]
                                          (run! (assoc ctx
                                                       :db        :stub-db
                                                       :meta-db   :stub-meta-db
                                                       :db-name   "stub")))
                    llm/make-messages-fn-from-opts
                    (fn [_] {:invoke-fn (fn [_] {:text "" :usage {}})})
                    embed/get-cached-index (fn [_ _] nil)
                    agent/ask (fn [_ _ _ opts]
                                (reset! seen opts)
                                {:answer "ok" :status :answered :usage {}})]
        (testing "10000 clamps to 50"
          (c-ask/do-ask {:question "q" :max-iterations 10000})
          (is (= 50 (:max-iterations @seen))
              (str "expected :max-iterations 50, got: " (pr-str @seen))))
        (testing "user-supplied value below cap passes through"
          (reset! seen nil)
          (c-ask/do-ask {:question "q" :max-iterations 7})
          (is (= 7 (:max-iterations @seen))))
        (testing "absent value defaults to 10"
          (reset! seen nil)
          (c-ask/do-ask {:question "q"})
          (is (= 10 (:max-iterations @seen))
              (str "expected default :max-iterations 10, got: " (pr-str @seen))))))))

(deftest parse-args-analyze-no-promote-flag
  (testing "analyze --no-promote parses to :no-promote true so the CLI
            can plumb it through to analyze-repo!. HTTP and the MCP
            tool description already expose this flag; the CLI was the
            only surface where bypassing the content-addressed
            promotion cache wasn't reachable."
    (let [result (cli/parse-args ["analyze" "--no-promote" "/some/path"])]
      (is (= "analyze" (:subcommand result)))
      (is (= true (:no-promote result))
          (str "expected :no-promote true on parsed opts; got: "
               (pr-str result)))))
  (testing "absent flag leaves :no-promote unset (falsy)"
    (let [result (cli/parse-args ["analyze" "/some/path"])]
      (is (= "analyze" (:subcommand result)))
      (is (not (:no-promote result))))))
