(ns noumenon.sync-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.sync :as sync]))

(deftest valid-sha?-test
  (testing "accepts valid 40-char hex SHA"
    (is (sync/valid-sha? "abc123def456789012345678901234567890abcd"))
    (is (sync/valid-sha? "0000000000000000000000000000000000000000")))
  (testing "rejects invalid SHAs"
    (is (not (sync/valid-sha? nil)))
    (is (not (sync/valid-sha? "")))
    (is (not (sync/valid-sha? "abc123")))
    (is (not (sync/valid-sha? "ABCDEF0000000000000000000000000000000000")))
    (is (not (sync/valid-sha? "abc123def456789012345678901234567890abcd; rm -rf /")))
    (is (not (sync/valid-sha? "--option-injection")))))
