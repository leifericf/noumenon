(ns noumenon.calls-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.calls :as calls]))

;; Fake entity IDs for testing
(def file-a 100)
(def file-b 200)
(def seg-a1 1001) ;; file-a, name "foo"
(def seg-a2 1002) ;; file-a, name "bar"
(def seg-b1 2001) ;; file-b, name "baz"
(def seg-b2 2002) ;; file-b, name "foo" (same name as a1, different file)

(def name-idx
  {:by-file {file-a {"foo" #{seg-a1} "bar" #{seg-a2}}
             file-b {"baz" #{seg-b1} "foo" #{seg-b2}}}
   :global  {"foo" #{seg-a1 seg-b2} "bar" #{seg-a2} "baz" #{seg-b1}}})

(def import-idx
  {file-a #{file-b}})

;; --- Same-file resolution ---

(deftest same-file-exact-match
  (is (= seg-a2 (calls/resolve-call "bar" file-a name-idx import-idx))))

(deftest same-file-stripped-qualifier
  (testing "strips namespace prefix (ns/name)"
    (is (= seg-a2 (calls/resolve-call "myns/bar" file-a name-idx import-idx)))))

(deftest same-file-stripped-module-qualifier
  (testing "strips module prefix (mod.name)"
    (is (= seg-a2 (calls/resolve-call "some.module.bar" file-a name-idx import-idx)))))

;; --- Cross-file resolution via imports ---

(deftest imported-file-exact-match
  (testing "resolves to imported file's segment"
    (is (= seg-b1 (calls/resolve-call "baz" file-a name-idx import-idx)))))

(deftest imported-file-stripped-qualifier
  (testing "resolves stripped name in imported file"
    (is (= seg-b1 (calls/resolve-call "mod/baz" file-a name-idx import-idx)))))

;; --- Ambiguity handling ---

(deftest ambiguous-imported-returns-nil
  (testing "foo exists in both file-a (same-file) and file-b (imported) — same-file wins"
    (is (= seg-a1 (calls/resolve-call "foo" file-a name-idx import-idx)))))

(deftest ambiguous-global-returns-nil
  (testing "from file-b (no imports), foo exists only locally — resolves"
    (is (= seg-b2 (calls/resolve-call "foo" file-b name-idx {})))))

;; --- Not found ---

(deftest not-found-returns-nil
  (is (nil? (calls/resolve-call "nonexistent" file-a name-idx import-idx))))

(deftest not-found-no-imports
  (testing "file-b has no imports, baz is local"
    (is (nil? (calls/resolve-call "bar" file-b name-idx {})))))
