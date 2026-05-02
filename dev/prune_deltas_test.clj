(ns prune-deltas-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [prune-deltas :as p]))

(deftest parse-name-shape
  (testing "standard repo + simple branch (with disambiguator hash6)"
    (is (= {:repo "noumenon" :branch "feat-branch-aware-graph" :basis7 "abcdef0"}
           (p/parse-name "noumenon__feat-branch-aware-graph-aabbcc__abcdef0"))))
  (testing "branch name containing __ round-trips correctly — anchoring the
            parser on the trailing -<hash6>__<basis7> means a literal branch
            name like `feat__under` is preserved as the branch instead of
            being split between repo and branch parts"
    (is (= {:repo "noumenon" :branch "feat__under" :basis7 "31389f9"}
           (p/parse-name "noumenon__feat__under-54c4da__31389f9"))))
  (testing "ambiguity heuristic: when both repo and branch could plausibly
            contain __, the parser prefers a single-underscore-free repo and
            attributes the __ to the branch (branches commonly use double
            underscore separators; repo basenames rarely do)"
    (is (= {:repo "weird" :branch "name__feature" :basis7 "1234567"}
           (p/parse-name "weird__name__feature-aabbcc__1234567"))))
  (testing "names without basis suffix don't parse"
    (is (nil? (p/parse-name "no-basis-suffix")))
    (is (nil? (p/parse-name "name__short-aabbcc__zzz"))))
  (testing "non-hex basis is rejected"
    (is (nil? (p/parse-name "name__branch-aabbcc__notahex"))))
  (testing "pre-disambiguator format (no -<hash6> suffix on branch) does NOT
            parse — the disambiguator landed before this parser change, and
            stale pre-disambiguator deltas are expected to be re-created
            rather than supported in two formats"
    (is (nil? (p/parse-name "noumenon__feat-branch-aware-graph__abcdef0")))))

(deftest classify-uses-trunk-existence
  (let [tmp (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path tmp "live-repo"))
    (with-redefs [p/trunk-data-dir tmp]
      (testing "trunk dir exists → live"
        (is (= :live (p/classify {:repo "live-repo"}))))
      (testing "trunk dir missing → trunk-missing"
        (is (= :trunk-missing (p/classify {:repo "ghost-repo"})))))))

(deftest deltas-dir-points-at-system-subdir
  (testing "deltas-dir must include the Datomic system subdir 'noumenon' so
            list-deltas walks the actual delta DBs, not the parent that
            contains a single 'noumenon' system entry. Without this, a
            confirmed deletion would wipe every delta on the machine."
    (is (clojure.string/ends-with? p/deltas-dir "/.noumenon/deltas/noumenon"))))

(deftest list-deltas-skips-system-dir-itself
  (let [parent  (str (fs/create-temp-dir))
        system  (str (fs/path parent "noumenon"))
        live    (str (fs/path system "myrepo__main-aabbcc__abcdef0"))
        broken  (str (fs/path system "garbage-not-parseable"))
        trunk   (str (fs/create-temp-dir))]
    (fs/create-dirs system)
    (fs/create-dirs live)
    (fs/create-dirs broken)
    (fs/create-dirs (fs/path trunk "myrepo"))
    (with-redefs [p/deltas-dir system
                  p/trunk-data-dir trunk]
      (let [rows (p/list-deltas)
            by-status (group-by :status rows)]
        (is (= 2 (count rows)) "system dir itself is not a row; the two children are")
        (is (= 1 (count (:live by-status))))
        (is (= 1 (count (:unparseable by-status))))
        (is (= "myrepo__main-aabbcc__abcdef0" (:name (first (:live by-status))))
            "live row points at the actual delta dir, not the system dir")))))
