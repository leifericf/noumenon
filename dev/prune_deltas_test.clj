(ns prune-deltas-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [prune-deltas :as p]))

(deftest parse-name-shape
  (testing "standard repo + simple branch"
    (is (= {:repo "noumenon" :branch "feat-branch-aware-graph" :basis7 "abcdef0"}
           (p/parse-name "noumenon__feat-branch-aware-graph__abcdef0"))))
  (testing "repo name itself contains __ — separator splits greedily from end"
    (is (= {:repo "weird__name" :branch "feature" :basis7 "1234567"}
           (p/parse-name "weird__name__feature__1234567"))))
  (testing "names without basis suffix don't parse"
    (is (nil? (p/parse-name "no-basis-suffix")))
    (is (nil? (p/parse-name "name__short__zzz"))))
  (testing "non-hex basis is rejected"
    (is (nil? (p/parse-name "name__branch__notahex")))))

(deftest classify-uses-trunk-existence
  (let [tmp (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path tmp "live-repo"))
    (with-redefs [p/trunk-data-dir tmp]
      (testing "trunk dir exists → live"
        (is (= :live (p/classify {:repo "live-repo"}))))
      (testing "trunk dir missing → trunk-missing"
        (is (= :trunk-missing (p/classify {:repo "ghost-repo"})))))))
