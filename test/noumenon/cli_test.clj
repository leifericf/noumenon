(ns noumenon.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.agent :as agent]
            [noumenon.ask-store :as ask-store]
            [noumenon.cli :as cli]
            [noumenon.cli.commands.ask :as c-ask]
            [noumenon.cli.commands.introspect :as c-intro]
            [noumenon.cli.util :as cu]
            [noumenon.embed :as embed]
            [noumenon.git :as git]
            [noumenon.introspect :as introspect]
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

(deftest do-ask-saves-session-to-meta-db
  (testing "CLI do-ask must persist an ask-session record to the meta
            DB just like the HTTP /api/ask handler does. Without it,
            CLI ask calls are invisible to the introspect loop's
            feedback/training signal, and `noum ask --continue-from
            <id>` can't reference its own prior sessions."
    (let [save-args (atom nil)]
      (with-redefs [cu/with-valid-repo  (fn [_opts run!] (run! {:db-dir "/tmp"}))
                    cu/with-existing-db (fn [ctx run!]
                                          (run! (assoc ctx
                                                       :db        :stub-db
                                                       :meta-db   :stub-meta-db
                                                       :meta-conn :stub-meta-conn
                                                       :db-name   "stub")))
                    llm/make-messages-fn-from-opts
                    (fn [_] {:invoke-fn (fn [_] {:text "" :usage {}})})
                    embed/get-cached-index (fn [_ _] nil)
                    agent/ask (fn [_ _ _ _]
                                {:answer "ok" :status :answered :usage {}})
                    ask-store/save-session!
                    (fn [_meta-conn _result opts]
                      (reset! save-args opts)
                      "test-session-id")]
        (c-ask/do-ask {:question "what?"})
        (let [opts @save-args]
          (is (some? opts) "save-session! must have been invoked")
          (is (= :cli (:channel opts))
              (str ":channel must be :cli; got: " (:channel opts)))
          (is (= "what?" (:question opts)))
          (is (= "stub" (:repo opts)))
          (is (instance? java.util.Date (:started-at opts)))
          (is (number? (:duration-ms opts))
              (str ":duration-ms must be a number; got: "
                   (pr-str (:duration-ms opts)))))))))

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

(deftest do-introspect-disables-git-commit-on-bare-repo
  (testing "Passing --git-commit against a bare git repo (no working
            tree) must not propagate :git-commit? true into the
            introspect run-loop. HTTP already gates this; CLI now
            matches so a user who accidentally points introspect at a
            bare clone doesn't get a confusing failure deep in the
            commit step."
    (let [seen (atom nil)]
      (with-redefs [cu/with-valid-repo  (fn [_opts run!] (run! {:db-dir "/tmp"
                                                                :repo-path "/tmp/bare.git"}))
                    cu/with-existing-db (fn [ctx run!]
                                          (run! (assoc ctx
                                                       :db        :stub-db
                                                       :meta-db   :stub-meta-db
                                                       :meta-conn :stub-meta-conn
                                                       :db-name   "stub")))
                    llm/make-messages-fn-from-opts
                    (fn [_] {:invoke-fn (fn [_] {:text "" :usage {}})})
                    git/bare-repo? (fn [_] true)
                    introspect/run-loop! (fn [opts]
                                           (reset! seen opts)
                                           {:improvements 0 :iterations 0
                                            :final-score 0.0 :run-id "test"})]
        (c-intro/do-introspect {:repo-path "/tmp/bare.git" :git-commit true})
        (is (false? (boolean (:git-commit? @seen)))
            (str "expected :git-commit? false on bare repo; got: "
                 (pr-str (:git-commit? @seen))))))))

(deftest parse-args-query-limit-flag
  (testing "noum query --limit 25 parses to :limit 25 so the CLI can
            cap result-set size like HTTP /api/query does."
    (let [result (cli/parse-args ["query" "--limit" "25" "recent-commits" "."])]
      (is (= "query" (:subcommand result)))
      (is (= 25 (:limit result))
          (str "expected :limit 25, got: " (pr-str result)))))
  (testing "absent flag leaves :limit unset"
    (let [result (cli/parse-args ["query" "recent-commits" "."])]
      (is (nil? (:limit result))))))

(deftest do-introspect-honors-git-commit-on-working-tree
  (testing "On a regular (non-bare) repo, --git-commit propagates
            unchanged so the user opt-in works as documented."
    (let [seen (atom nil)]
      (with-redefs [cu/with-valid-repo  (fn [_opts run!] (run! {:db-dir "/tmp"
                                                                :repo-path "/tmp/work"}))
                    cu/with-existing-db (fn [ctx run!]
                                          (run! (assoc ctx
                                                       :db        :stub-db
                                                       :meta-db   :stub-meta-db
                                                       :meta-conn :stub-meta-conn
                                                       :db-name   "stub")))
                    llm/make-messages-fn-from-opts
                    (fn [_] {:invoke-fn (fn [_] {:text "" :usage {}})})
                    git/bare-repo? (fn [_] false)
                    introspect/run-loop! (fn [opts]
                                           (reset! seen opts)
                                           {:improvements 0 :iterations 0
                                            :final-score 0.0 :run-id "test"})]
        (c-intro/do-introspect {:repo-path "/tmp/work" :git-commit true})
        (is (true? (boolean (:git-commit? @seen)))
            (str "expected :git-commit? true on working-tree repo; got: "
                 (pr-str (:git-commit? @seen))))))))
