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

(deftest base-url-honors-loopback-with-or-without-scheme
  (testing "bare localhost / 127.0.0.1 are allowed"
    (is (= "http://localhost:7895"
           (api/base-url {:host "localhost:7895" :insecure true})))
    (is (= "http://127.0.0.1:7895"
           (api/base-url {:host "127.0.0.1:7895" :insecure true}))))
  (testing "scheme-prefixed localhost / 127.0.0.1 are allowed (the SSRF check used to block these)"
    (is (= "http://localhost:7895"
           (api/base-url {:host "http://localhost:7895" :insecure true})))
    (is (= "http://127.0.0.1:7895"
           (api/base-url {:host "http://127.0.0.1:7895" :insecure true})))
    (is (= "https://localhost:7895"
           (api/base-url {:host "https://localhost:7895"}))))
  (testing "no host falls back to local-daemon port"
    (is (= "http://127.0.0.1:7895"
           (api/base-url {:port 7895})))))
