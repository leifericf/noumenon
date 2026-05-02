(ns noumenon.query-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.artifacts :as artifacts]
            [noumenon.db :as db]
            [noumenon.query :as query]
            [noumenon.test-helpers :as th]))

;; --- Tier 0: Loading tests (via artifacts, backed by Datomic) ---

(defn- meta-db
  "Get a seeded meta-db for testing."
  []
  (let [conn (db/ensure-meta-db :mem)]
    (d/db conn)))

(deftest load-rules-returns-vector
  (let [rules (artifacts/load-rules (meta-db))]
    (is (vector? rules))
    (is (pos? (count rules)))))

(deftest load-named-query-known
  (let [q (artifacts/load-named-query (meta-db) "files-by-complexity")]
    (is (map? q))
    (is (= "files-by-complexity" (:name q)))
    (is (vector? (:query q)))))

(deftest load-named-query-unknown
  (is (nil? (artifacts/load-named-query (meta-db) "nonexistent-query"))))

(deftest list-active-query-names-returns-known
  (let [names (artifacts/list-active-query-names (meta-db))]
    (is (some #{"files-by-complexity"} names))
    (is (some #{"co-changed-files"} names))
    (is (some #{"boundary-crossings"} names))))

(deftest list-active-query-names-sorted
  (let [names (artifacts/list-active-query-names (meta-db))]
    (is (= names (sort names)))))

(deftest query-manifest-matches-query-files
  (let [dir        (io/file (io/resource "queries/"))
        file-names (->> (.listFiles dir)
                        (keep (fn [f]
                                (let [n (.getName f)]
                                  (when (and (str/ends-with? n ".edn")
                                             (not (#{"rules.edn" "index.edn"} n)))
                                    (str/replace n #"\.edn$" "")))))
                        set)
        manifest   (set (artifacts/list-active-query-names (meta-db)))]
    (is (= file-names manifest))))

(deftest all-named-queries-load
  (let [mdb (meta-db)]
    (doseq [name (artifacts/list-active-query-names mdb)]
      (let [q (artifacts/load-named-query mdb name)]
        (is (map? q) (str name " should load as map"))
        (is (vector? (:query q)) (str name " should have :query vector"))))))

;; --- Tier 1: Integration tests ---

(defn- make-conn []
  (th/make-test-conn "query-test"))

(deftest files-by-complexity-query
  (let [conn (make-conn)
        mdb  (meta-db)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/lang :clojure
                                 :file/size 100 :sem/complexity :simple}
                                {:file/path "src/b.clj" :file/lang :clojure
                                 :file/size 200 :sem/complexity :complex}]})
    (let [{:keys [ok error]} (query/run-named-query mdb (d/db conn) "files-by-complexity")]
      (is (nil? error))
      (is (= 2 (count ok)))
      (is (= #{"src/a.clj" "src/b.clj"} (set (map first ok)))))))

(deftest files-by-layer-query
  (let [conn (make-conn)
        mdb  (meta-db)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/size 100
                                 :arch/layer :core}]})
    (let [{:keys [ok]} (query/run-named-query mdb (d/db conn) "files-by-layer")]
      (is (= 1 (count ok)))
      (is (= "src/a.clj" (ffirst ok))))))

(deftest unknown-query-returns-error
  (let [conn (make-conn)
        mdb  (meta-db)
        {:keys [ok error]} (query/run-named-query mdb (d/db conn) "bogus-query")]
    (is (nil? ok))
    (is (string? error))
    (is (re-find #"Unknown query" error))))

(deftest parameterized-query-without-params-reports-missing
  (let [conn (make-conn)
        mdb  (meta-db)
        {:keys [ok error]} (query/run-named-query mdb (d/db conn) "file-imports")]
    (is (nil? ok))
    (is (string? error))
    (is (re-find #"Missing required inputs" error))
    (is (re-find #"file-path" error))))

(deftest parameterized-query-with-params-does-not-report-missing
  (let [conn (make-conn)
        mdb  (meta-db)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/size 100}]})
    (let [{:keys [error]} (query/run-named-query mdb (d/db conn) "file-imports"
                                                 {:file-path "src/a.clj"})]
      (is (nil? error)))))

(deftest transitive-deps-rule
  (let [conn  (make-conn)
        rules (artifacts/load-rules (meta-db))]
    ;; A depends-on B, B depends-on C
    (d/transact conn {:tx-data [{:component/name "A"
                                 :component/depends-on "comp-b"}
                                {:db/id "comp-b"
                                 :component/name "B"
                                 :component/depends-on "comp-c"}
                                {:db/id "comp-c"
                                 :component/name "C"}]})
    (let [results (d/q '[:find ?from-name ?to-name
                         :in $ %
                         :where
                         [?from :component/name ?from-name]
                         (transitive-dep ?from ?to)
                         [?to :component/name ?to-name]]
                       (d/db conn) rules)
          pairs   (set (map vec results))]
      ;; Should include A->B, A->C (transitive), B->C
      (is (>= (count pairs) 3))
      (is (contains? pairs ["A" "B"]))
      (is (contains? pairs ["A" "C"]))
      (is (contains? pairs ["B" "C"])))))

(deftest co-changed-files-rule
  (let [conn (make-conn)
        mdb  (meta-db)]
    ;; Create files and commits that change them together
    (d/transact conn {:tx-data [{:file/path "src/x.clj" :file/size 10}
                                {:file/path "src/y.clj" :file/size 20}]})
    ;; 3 commits that change both files
    (dotimes [i 3]
      (d/transact conn {:tx-data [{:git/sha (str "sha-" i)
                                   :git/type :commit
                                   :commit/message (str "commit " i)
                                   :commit/changed-files [[:file/path "src/x.clj"]
                                                          [:file/path "src/y.clj"]]}]}))
    (let [{:keys [ok]} (query/run-named-query mdb (d/db conn) "co-changed-files")]
      (is (= 1 (count ok)) "One pair")
      (let [[p1 p2 cnt] (first ok)]
        (is (= #{"src/x.clj" "src/y.clj"} #{p1 p2}))
        (is (= 3 cnt))))))

(deftest top-contributors-query
  (let [conn (make-conn)
        mdb  (meta-db)]
    (d/transact conn {:tx-data [{:db/id "author-1"
                                 :person/email "a@test.com"
                                 :person/name "Alice"}
                                {:git/sha "sha-1" :git/type :commit
                                 :commit/message "one"
                                 :commit/author "author-1"}
                                {:git/sha "sha-2" :git/type :commit
                                 :commit/message "two"
                                 :commit/author "author-1"}]})
    (let [{:keys [ok]} (query/run-named-query mdb (d/db conn) "top-contributors")]
      (is (= 1 (count ok)))
      (let [[name email cnt] (first ok)]
        (is (= "Alice" name))
        (is (= "a@test.com" email))
        (is (= 2 cnt))))))

;; --- Phase E1: Federation injection ---

(deftest inject-exclusions-empty-passthrough
  (let [q '[:find ?path :where [?file :file/path ?path]]]
    (is (= q (query/inject-exclusions q [])))
    (is (= q (query/inject-exclusions q nil)))))

(deftest inject-exclusions-appends-not-clauses
  (let [q       '[:find ?path :where [?file :file/path ?path]]
        out     (query/inject-exclusions q ["src/a.clj" "src/b.clj"])
        clauses (drop 4 out)]
    (is (= 6 (count out)))
    (is (= '(not [?file :file/path "src/a.clj"]) (first clauses)))
    (is (= '(not [?file :file/path "src/b.clj"]) (second clauses)))))

(deftest federation-safe-flag-loaded-from-edn
  (let [mdb (meta-db)
        safe   (artifacts/load-named-query mdb "orphan-files")
        unsafe (artifacts/load-named-query mdb "files-by-complexity")]
    (is (true? (:federation-safe? safe)))
    (is (false? (:federation-safe? unsafe)))))

(deftest run-named-query-surfaces-federation-flag
  (let [conn (make-conn)
        mdb  (meta-db)
        safe   (query/run-named-query mdb (d/db conn) "orphan-files")
        unsafe (query/run-named-query mdb (d/db conn) "files-by-complexity")]
    (is (true? (:federation-safe? safe)))
    (is (false? (:federation-safe? unsafe)))))

(deftest run-named-query-injects-exclusions-when-safe
  (let [conn (make-conn)
        mdb  (meta-db)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/size 100 :file/lang :clojure}
                                {:file/path "src/b.clj" :file/size 200 :file/lang :clojure}
                                {:file/path "src/c.clj" :file/size 300 :file/lang :clojure}]})
    (let [{:keys [ok]} (query/run-named-query mdb (d/db conn) "orphan-files" {})
          all-paths    (set (map first ok))
          {:keys [ok]} (query/run-named-query mdb (d/db conn) "orphan-files" {}
                                              {:exclude-paths ["src/a.clj"]})
          remaining    (set (map first ok))]
      (is (contains? all-paths "src/a.clj"))
      (is (not (contains? remaining "src/a.clj")) "excluded path is gone")
      (is (= (disj all-paths "src/a.clj") remaining)))))

(deftest run-named-query-ignores-exclusions-when-unsafe
  (let [conn (make-conn)
        mdb  (meta-db)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/size 100 :sem/complexity :simple}
                                {:file/path "src/b.clj" :file/size 200 :sem/complexity :complex}]})
    (let [{:keys [ok]} (query/run-named-query mdb (d/db conn) "files-by-complexity" {}
                                              {:exclude-paths ["src/a.clj"]})
          paths (set (map first ok))]
      ;; files-by-complexity uses ?e not ?file — exclusion is a no-op (the query
      ;; isn't federation-safe so inject-exclusions is bypassed entirely).
      (is (contains? paths "src/a.clj"))
      (is (contains? paths "src/b.clj")))))

(deftest delta-tombstoned-paths-from-delta-db
  (let [conn (make-conn)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/size 100}
                                {:file/path "src/b.clj" :file/deleted? true}
                                {:file/path "src/c.clj" :file/deleted? true}]})
    (is (= #{"src/b.clj" "src/c.clj"}
           (set (query/delta-tombstoned-paths (d/db conn))))
        "only files with :file/deleted? true are listed; live entries are not")))

(deftest delta-added-paths-vs-trunk
  (let [trunk (th/make-test-conn "added-trunk")
        delta (th/make-test-conn "added-delta")]
    (d/transact trunk {:tx-data [{:file/path "src/a.clj"} {:file/path "src/b.clj"}]})
    (d/transact delta {:tx-data [{:file/path "src/a.clj" :file/size 999}    ;; modified
                                 {:file/path "src/c.clj" :file/size 100}    ;; added
                                 {:file/path "src/b.clj" :file/deleted? true}]}) ;; tombstoned
    (is (= #{"src/c.clj"}
           (set (query/delta-added-paths (d/db trunk) (d/db delta))))
        "only files in delta but not in trunk count as added — modified and tombstoned excluded")))

(deftest run-federated-query-tombstone-only-merge
  (let [trunk (th/make-test-conn "fed-trunk-test")
        delta (th/make-test-conn "fed-delta-test")
        mdb   (meta-db)]
    ;; Trunk has a.clj, b.clj, c.clj as orphans
    (d/transact trunk {:tx-data [{:file/path "src/a.clj" :file/size 100 :file/lang :clojure}
                                 {:file/path "src/b.clj" :file/size 200 :file/lang :clojure}
                                 {:file/path "src/c.clj" :file/size 300 :file/lang :clojure}]})
    ;; Delta: a.clj modified, c.clj tombstoned, d.clj added
    (d/transact delta {:tx-data [{:file/path "src/a.clj" :file/size 999 :file/lang :clojure}
                                 {:file/path "src/c.clj" :file/deleted? true}
                                 {:file/path "src/d.clj" :file/size 50 :file/lang :clojure}]})
    (let [result (query/run-federated-query mdb (d/db trunk) (d/db delta)
                                            "orphan-files" {})
          paths  (set (map first (:ok result)))]
      (is (true? (:federation-safe? result)))
      (is (= :tombstone-only (:federation-mode result)))
      (testing "tombstone-only merge keeps trunk's view of modified files,
                excludes tombstoned files, and does NOT include added files —
                the delta DB lacks the joins (here :file/imports) that the
                query needs, so adding d.clj from the delta would falsely
                report it as an orphan"
        (is (= 2 (:trunk-count result)) "trunk has a and b (c excluded by tombstone)")
        (is (contains? paths "src/a.clj") "modified file a.clj keeps trunk's row")
        (is (contains? paths "src/b.clj") "unchanged file b.clj is in trunk view")
        (is (not (contains? paths "src/c.clj")) "tombstoned file c.clj is gone")
        (is (= 0 (:delta-count result)) "tombstone-only merge contributes no delta rows")
        (is (not (contains? paths "src/d.clj")) "added file is NOT included — would be a false orphan")))))

(deftest run-federated-query-added-files-merge-mode
  (testing "queries declared :added-files-merge include delta rows for files
            ADDED in the branch — opt-in for queries that join only on
            :stable attrs, where the delta has the data the query needs"
    (let [trunk     (th/make-test-conn "fed-add-trunk")
          delta     (th/make-test-conn "fed-add-delta")
          meta-conn (db/ensure-meta-db :mem)
          ;; Synthetic stable-only query: list files with :file/lang.
          ;; :file/lang is :stable, so :added-files-merge is permitted.
          _         (d/transact meta-conn
                                {:tx-data [{:artifact.query/name             "test-stable-by-lang"
                                            :artifact.query/description      "test query"
                                            :artifact.query/query-edn        (pr-str '[:find ?path
                                                                                       :where
                                                                                       [?file :file/path ?path]
                                                                                       [?file :file/lang _]])
                                            :artifact.query/active           true
                                            :artifact.query/federation-mode  :added-files-merge
                                            :artifact.query/federation-safe? true}]})
          mdb       (d/db meta-conn)]
      (d/transact trunk {:tx-data [{:file/path "src/a.clj" :file/lang :clojure}
                                   {:file/path "src/b.clj" :file/lang :clojure}]})
      (d/transact delta {:tx-data [{:file/path "src/a.clj" :file/lang :clojure}    ;; modified
                                   {:file/path "src/c.clj" :file/lang :clojure}    ;; added
                                   {:file/path "src/b.clj" :file/deleted? true}]}) ;; tombstoned
      (let [result (query/run-federated-query mdb (d/db trunk) (d/db delta)
                                              "test-stable-by-lang" {})
            paths  (set (map first (:ok result)))]
        (is (= :added-files-merge (:federation-mode result)))
        (is (contains? paths "src/a.clj") "trunk's a.clj kept")
        (is (not (contains? paths "src/b.clj")) "tombstoned b.clj excluded")
        (is (contains? paths "src/c.clj") "delta-added c.clj included via :added-files-merge")
        (is (= 1 (:delta-count result)) "delta contributed exactly one row")))))

(deftest validate-federation-mode-allows-stable-only-joins
  (testing "validate-federation-mode! accepts an :added-files-merge query
            whose :where touches only :stable attrs"
    (let [mdb (meta-db)
          q   {:federation-mode :added-files-merge
               :query '[:find ?path :where [?file :file/path ?path] [?file :file/lang _]]}]
      (is (nil? (artifacts/validate-federation-mode! mdb "stable-by-lang" q))
          "all attrs are :stable — no throw, returns nil"))))

(deftest validate-federation-mode-rejects-trunk-only-joins
  (testing "an :added-files-merge query that touches a non-:stable attr
            (e.g. :file/imports) is rejected at seed time — without this
            guard a contributor could re-introduce the orphan-files-style
            false positive"
    (let [mdb (meta-db)
          q   {:federation-mode :added-files-merge
               :query '[:find ?path :where [?file :file/path ?path] [?file :file/imports _]]}
          err (try (artifacts/validate-federation-mode! mdb "imports-merge" q)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? err))
      (is (re-find #":file/imports" (.getMessage err))
          "error message names the offending attr"))))

(deftest validate-federation-mode-rejects-rule-using-added-files-merge
  (testing "queries that use rules (`%`) cannot opt into :added-files-merge —
            rules can indirectly touch trunk-only attrs and walking them is
            out of scope for v1"
    (let [mdb (meta-db)
          q   {:federation-mode :added-files-merge
               :uses-rules      true
               :query '[:find ?path :in $ % :where (some-rule ?file ?path)]}
          err (try (artifacts/validate-federation-mode! mdb "rule-merge" q)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? err))
      (is (re-find #"rules" (.getMessage err))))))

(deftest validate-federation-mode-skips-tombstone-only-and-absent
  (testing "no validation runs when mode is :tombstone-only or absent —
            the validator only enforces the stable-only rule for the opt-in"
    (let [mdb (meta-db)
          tombstone {:federation-mode :tombstone-only
                     :query '[:find ?path :where [?file :file/path ?path] [?file :file/imports _]]}
          absent    {:query '[:find ?path :where [?file :file/path ?path] [?file :file/imports _]]}]
      (is (nil? (artifacts/validate-federation-mode! mdb "x" tombstone)))
      (is (nil? (artifacts/validate-federation-mode! mdb "y" absent))))))

(deftest run-federated-query-falls-back-when-unsafe
  (let [trunk (th/make-test-conn "fed-trunk-unsafe")
        delta (th/make-test-conn "fed-delta-unsafe")
        mdb   (meta-db)]
    (d/transact trunk {:tx-data [{:file/path "src/a.clj" :file/size 100 :sem/complexity :simple}
                                 {:file/path "src/b.clj" :file/size 200 :sem/complexity :complex}]})
    (d/transact delta {:tx-data [{:file/path "src/a.clj" :file/size 100 :sem/complexity :very-complex}]})
    (let [result (query/run-federated-query mdb (d/db trunk) (d/db delta)
                                            "files-by-complexity" {})]
      (is (false? (:federation-safe? result)))
      ;; Trunk-only since the query isn't safe
      (is (= 0 (:delta-count result)))
      (is (= 2 (:trunk-count result))))))
