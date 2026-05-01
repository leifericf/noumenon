(ns noumenon.query-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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
