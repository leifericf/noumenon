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

(deftest validate-repo-path-rejects-non-strings
  (testing "non-nil non-string repo-path returns a `must be a string`
            reason instead of letting (io/file <non-string>) throw an
            IllegalArgumentException downstream. Without this, a JSON
            request like {\"repo_path\": 42} would surface as a 500
            instead of a clean 400 at the validation boundary."
    (doseq [bad [42 ["a"] {:k 1} :kw true]]
      (let [reason (util/validate-repo-path bad)]
        (is (string? reason) (str "expected reason string for " (pr-str bad)))
        (is (re-find #"string" reason)
            (str "reason mentions string for " (pr-str bad)))))))

(deftest validate-repo-path-rejects-overlong-strings
  (testing "repo-path strings over max-repo-path-len return a length
            reason — caps the input at the boundary rather than letting
            io/file walk a multi-MB string"
    (let [reason (util/validate-repo-path (apply str (repeat 5000 "a")))]
      (is (string? reason))
      (is (re-find #"4096|exceeds" reason)
          "reason mentions the cap or 'exceeds'"))))

(deftest validate-repo-path-existing-fs-reasons-unchanged
  (testing "valid string that doesn't exist still returns the existing
            FS reason"
    (is (= "does not exist"
           (util/validate-repo-path "/tmp/definitely-not-a-real-noumenon-path-xyz"))))
  (testing "nil is unchanged — returns nil"
    (is (nil? (util/validate-repo-path nil)))))
