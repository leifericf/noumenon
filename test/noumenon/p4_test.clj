(ns noumenon.p4-test
  "Adapter tests for the noumenon.p4 namespace. The library-side correctness
   lives in clj-p4's own tests; here we cover only the Noumenon-specific
   bits: depot-path detection, clone-path derivation, exclude-defaults
   loading from `p4-excludes.edn`, and trailer parsing for sync."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-p4.api :as api]
            [noumenon.p4 :as p4]))

(deftest depot-path-detection
  (testing "detects depot paths"
    (is (p4/depot-path? "//depot/project/main/..."))
    (is (p4/depot-path? "//stream/main/..."))
    (is (p4/depot-path? "//depot/game/Source/...")))
  (testing "rejects non-depot paths"
    (is (not (p4/depot-path? "/some/local/path")))
    (is (not (p4/depot-path? "https://github.com/foo/bar")))
    (is (not (p4/depot-path? "git@github.com:foo/bar.git")))
    (is (not (p4/depot-path? nil)))
    (is (not (p4/depot-path? "")))))

(deftest depot-to-clone-name
  (testing "derives clone directory name from depot path"
    (is (= "ProjectA-main" (p4/depot->clone-name "//depot/ProjectA/main/...")))
    (is (= "main"          (p4/depot->clone-name "//stream/main/...")))
    (is (= "game-Source"   (p4/depot->clone-name "//depot/game/Source/...")))
    (is (= "depot"         (p4/depot->clone-name "//depot/...")))))

(deftest clone-path-derivation
  (testing "builds local clone path from depot path"
    (is (= "data/repos/ProjectA-main"
           (p4/clone-path "//depot/ProjectA/main/...")))))

(deftest validate-throws-on-bad-input
  (is (thrown? clojure.lang.ExceptionInfo
               (p4/validate-depot-path! "not-a-depot-path"))))

(deftest available?-returns-boolean
  ;; Doesn't assert true/false — the test environment may or may not have
  ;; the p4 binary. Just check the call succeeds and returns a boolean.
  (is (boolean? (p4/available?))))

(deftest sync-passes-exclude-policy
  (testing "sync! computes the same default excludes as clone! and forwards them"
    (let [captured (atom nil)]
      (with-redefs [;; Stub out the trailer-parse → return a fake stream
                    p4/depot-path? (fn [_] true)
                    ;; Intercept the underlying clj-p4 call
                    api/sync! (fn [m] (reset! captured m) {:synced 0})
                    ;; Stub clj-p4.shell.proc/run-checked! so last-commit-message
                    ;; returns a string carrying a git-p4 trailer (so sync!
                    ;; passes its trailer-presence guard).
                    clj-p4.shell.proc/run-checked!
                    (fn [_ & _]
                      {:exit 0
                       :stdout-bytes (.getBytes (str "subject\n\n"
                                                     "[git-p4: depot-paths = "
                                                     \" "//stream/main/" \" ": "
                                                     "change = 1]")
                                                "UTF-8")
                       :stderr "" :elapsed-ms 1})]
        (p4/sync! "/tmp/fake-repo")
        (let [{:keys [exclude]} @captured]
          (is (vector? exclude)
              "exclude vector should be present, not nil")
          (is (pos? (count exclude))
              "default p4-excludes.edn should produce at least one pattern"))))))
