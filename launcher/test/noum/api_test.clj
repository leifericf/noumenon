(ns noum.api-test
  "Unit tests for noum.api launcher helpers. Run via:
       bb -cp src:resources:test -m clojure.test/run-tests noum.api-test"
  (:require [clojure.test :refer [deftest is testing]]
            [noum.api :as api]))

(deftest launcher-setting-key?-routes-by-namespace-prefix
  (testing "federation/* keys are launcher-local"
    (is (true? (api/launcher-setting-key? "federation/auto-route")))
    (is (true? (api/launcher-setting-key? :federation/auto-route))))
  (testing "non-federation keys go through the daemon"
    (is (false? (api/launcher-setting-key? "ask/concurrency")))
    (is (false? (api/launcher-setting-key? "model"))))
  (testing "blank or single-segment keys are not launcher-local"
    (is (false? (api/launcher-setting-key? "")))
    (is (false? (api/launcher-setting-key? "no-namespace")))))

(deftest detect-federation-context-bails-on-local-mode
  (testing "nil conn (local mode) returns nil — never auto-federates"
    (is (nil? (api/detect-federation-context "/tmp" nil))))
  (testing "non-hosted conn (no :host) returns nil"
    (is (nil? (api/detect-federation-context "/tmp" {:port 7891})))))
