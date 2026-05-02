(ns noumenon.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.util :as util]))

(deftest validate-string-length!-allows-nil
  (testing "nil is allowed — optional fields stay optional"
    (is (nil? (util/validate-string-length! "branch" nil 256)))))

(deftest validate-string-length!-allows-strings-under-cap
  (testing "valid strings under the cap pass silently"
    (is (nil? (util/validate-string-length! "branch" "main" 256)))
    (is (nil? (util/validate-string-length! "branch" "" 256)))))

(deftest validate-string-length!-rejects-overlong-strings
  (testing "strings over the cap throw 400"
    (let [err (try (util/validate-string-length! "branch" (apply str (repeat 300 "a")) 256)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? err))
      (is (= 400 (:status (ex-data err))))
      (is (re-find #"branch" (.getMessage err)))
      (is (re-find #"256" (.getMessage err))))))

(deftest validate-string-length!-rejects-non-string-types
  (testing "non-nil non-strings throw 400 — without this guard, a JSON
            request like {\"branch\": [\"a\"]} flows past the validator
            and crashes downstream as a 500. The validator was written
            to enforce length-on-strings, but every caller relies on it
            as a type-AND-length boundary; making the type check
            explicit fixes that."
    (doseq [bad [123 ["a" "b"] {:k 1} :keyword true]]
      (let [err (try (util/validate-string-length! "branch" bad 256)
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? err) (str "expected throw for " (pr-str bad)))
        (is (= 400 (:status (ex-data err)))
            (str "status 400 for " (pr-str bad)))
        (is (re-find #"branch" (.getMessage err)))
        (is (re-find #"string" (.getMessage err))
            (str "message names the expected type for " (pr-str bad)))))))
