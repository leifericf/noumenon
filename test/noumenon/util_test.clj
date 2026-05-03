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

(deftest max-branch-name-len-fits-fs-component-with-typical-repo
  (testing "max-branch-name-len + the fixed surrounding bytes of the
            synthesized delta db-name (`<repo>__<safe-branch>-<hash6>__<basis7>`)
            must stay under the POSIX path-component limit (255 bytes) for a
            reasonable repo basename. Without this constraint, a request
            that passes branch validation crashes downstream as
            'File name too long' (500). Math: branch + 18 fixed
            (`__-<hash6>__<basis7>`) + repo. We require headroom for at
            least a 35-char repo name (covers most real-world basenames)."
    (let [fixed-overhead   18
          assumed-repo-len 35]
      (is (<= (+ util/max-branch-name-len fixed-overhead assumed-repo-len)
              255)
          (str "max-branch-name-len=" util/max-branch-name-len
               " leaves no FS-component headroom; lower it.")))))

(deftest validate-introspect-targets-rejects-unknown
  (testing "Unknown targets must surface as an ExceptionInfo with a
            user-facing message that names the offending value(s) and
            lists the valid set, so a typo never silently expands the
            run to all targets."
    (is (thrown? clojure.lang.ExceptionInfo
                 (util/validate-introspect-targets! "foobar"))
        "single unknown target rejected")
    (is (thrown? clojure.lang.ExceptionInfo
                 (util/validate-introspect-targets! "examples,foobar"))
        "mix of known + unknown rejected (any unknown is a typo)")
    (try
      (util/validate-introspect-targets! "foobar,quux")
      (is false "expected throw, got success")
      (catch clojure.lang.ExceptionInfo e
        (let [msg     (.getMessage e)
              user-msg (:user-message (ex-data e))]
          (is (re-find #"foobar" msg) (str "msg names the bad input; got: " msg))
          (is (re-find #"quux"   msg) (str "msg names every bad input; got: " msg))
          (is (re-find #"examples" msg)
              (str "msg lists at least one valid target; got: " msg))
          (is (string? user-msg)
              "ex-data carries :user-message for surface-level rendering"))))))

(deftest validate-introspect-targets-accepts-known
  (testing "All listed valid targets are accepted, alone or combined,
            including with whitespace around the commas."
    (is (nil? (util/validate-introspect-targets! "examples")))
    (is (nil? (util/validate-introspect-targets! "system-prompt")))
    (is (nil? (util/validate-introspect-targets! "rules")))
    (is (nil? (util/validate-introspect-targets! "code")))
    (is (nil? (util/validate-introspect-targets! "train")))
    (is (nil? (util/validate-introspect-targets!
               "examples,system-prompt,rules,code,train")))
    (is (nil? (util/validate-introspect-targets! "examples, code")))))

(deftest validate-introspect-targets-tolerates-nil-and-blank
  (testing "Absent target is the 'no filter' case and must pass through
            without error — only TYPO'D targets are a user mistake."
    (is (nil? (util/validate-introspect-targets! nil)))
    (is (nil? (util/validate-introspect-targets! "")))
    (is (nil? (util/validate-introspect-targets! "   ")))))
