(ns noum.api-test
  "Unit tests for noum.api launcher helpers. Run via:
       bb -cp src:resources:test -m clojure.test/run-tests noum.api-test"
  (:require [clojure.test :refer [deftest is testing]]
            [noum.api :as api]
            [noum.cli :as cli]))

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

(deftest blocked-address?-classifies-ipv6-without-reflection
  ;; Bug: bb's native-image lacks reflection metadata for Inet6Address's
  ;; instance methods (.isLoopbackAddress, .getAddress, etc.). Any host
  ;; whose DNS lookup returned an Inet6Address used to surface as
  ;; MissingReflectionRegistrationError. Verify against synthetic
  ;; addresses that the classifier returns the right answer with no
  ;; reflection error.
  (let [blocked? @#'noum.api/blocked-address?]
    (testing "IPv6 loopback ::1 is private"
      (is (true? (boolean (blocked? (java.net.InetAddress/getByName "::1"))))))
    (testing "IPv6 link-local fe80::1 is private"
      (is (true? (boolean (blocked? (java.net.InetAddress/getByName "fe80::1"))))))
    (testing "IPv6 unique local fd00::1 is private"
      (is (true? (boolean (blocked? (java.net.InetAddress/getByName "fd00::1"))))))
    (testing "IPv4 public 8.8.8.8 is not private"
      (is (false? (boolean (blocked? (java.net.InetAddress/getByName "8.8.8.8"))))))
    (testing "public IPv6 2001:4860:4860::8888 is not private (used to crash with MissingReflectionRegistrationError)"
      (is (false? (boolean (blocked? (java.net.InetAddress/getByName "2001:4860:4860::8888"))))))))

(deftest parse-args-accumulates-repeated-param
  (testing "a single --param parses normally"
    (let [parsed (cli/parse-args ["query" "name" "repo" "--param" "k=v"])]
      (is (= ["k=v"] (-> parsed :flags :param)))))
  (testing "repeated --param flags accumulate (the help text claims 'repeat command as needed')"
    (let [parsed (cli/parse-args ["query" "name" "repo"
                                  "--param" "k1=v1"
                                  "--param" "k2=v2"
                                  "--param" "k3=v3"])]
      (is (= ["k1=v1" "k2=v2" "k3=v3"] (-> parsed :flags :param))))))

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
