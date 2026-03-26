(ns noumenon.query-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.query :as query]))

;; --- Tier 0: Loading tests ---

(deftest load-rules-returns-vector
  (let [rules (query/load-rules)]
    (is (vector? rules))
    (is (pos? (count rules)))))

(deftest load-named-query-known
  (let [q (query/load-named-query "files-by-complexity")]
    (is (map? q))
    (is (= "files-by-complexity" (:name q)))
    (is (vector? (:query q)))))

(deftest load-named-query-unknown
  (is (nil? (query/load-named-query "nonexistent-query"))))

(deftest list-query-names-returns-known
  (let [names (query/list-query-names)]
    (is (some #{"files-by-complexity"} names))
    (is (some #{"co-changed-files"} names))
    (is (some #{"component-dependencies"} names))))

(deftest list-query-names-sorted
  (let [names (query/list-query-names)]
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
        manifest   (set (query/list-query-names))]
    (is (= file-names manifest))))

(deftest all-named-queries-load
  (doseq [name (query/list-query-names)]
    (let [q (query/load-named-query name)]
      (is (map? q) (str name " should load as map"))
      (is (vector? (:query q)) (str name " should have :query vector")))))

;; --- Tier 1: Integration tests ---

(defn- make-conn []
  (db/connect-and-ensure-schema :mem (str "query-test-" (random-uuid))))

(deftest files-by-complexity-query
  (let [conn (make-conn)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/lang :clojure
                                 :file/size 100 :sem/complexity :simple}
                                {:file/path "src/b.clj" :file/lang :clojure
                                 :file/size 200 :sem/complexity :complex}]})
    (let [{:keys [ok error]} (query/run-named-query (d/db conn) "files-by-complexity")]
      (is (nil? error))
      (is (= 2 (count ok)))
      (is (= #{"src/a.clj" "src/b.clj"} (set (map first ok)))))))

(deftest files-by-layer-query
  (let [conn (make-conn)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/size 100
                                 :arch/layer :core}]})
    (let [{:keys [ok]} (query/run-named-query (d/db conn) "files-by-layer")]
      (is (= 1 (count ok)))
      (is (= "src/a.clj" (ffirst ok))))))

(deftest unknown-query-returns-error
  (let [conn (make-conn)
        {:keys [ok error]} (query/run-named-query (d/db conn) "bogus-query")]
    (is (nil? ok))
    (is (string? error))
    (is (re-find #"Unknown query" error))))

(deftest parameterized-query-without-params-reports-missing
  (let [conn (make-conn)
        {:keys [ok error]} (query/run-named-query (d/db conn) "file-imports")]
    (is (nil? ok))
    (is (string? error))
    (is (re-find #"Missing required inputs" error))
    (is (re-find #"file-path" error))))

(deftest parameterized-query-with-params-does-not-report-missing
  (let [conn (make-conn)]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/size 100}]})
    (let [{:keys [error]} (query/run-named-query (d/db conn) "file-imports"
                                                 {:file-path "src/a.clj"})]
      (is (nil? error)))))

(deftest transitive-deps-rule
  (let [conn (make-conn)]
    ;; A depends-on B, B depends-on C
    (d/transact conn {:tx-data [{:component/name "A"
                                 :component/depends-on "comp-b"}
                                {:db/id "comp-b"
                                 :component/name "B"
                                 :component/depends-on "comp-c"}
                                {:db/id "comp-c"
                                 :component/name "C"}]})
    (let [{:keys [ok]} (query/run-named-query (d/db conn) "component-dependencies")]
      ;; Should include A->B, A->C (transitive), B->C
      (is (>= (count ok) 3))
      (let [pairs (set (map (fn [[from to]] [from to]) ok))]
        (is (contains? pairs ["A" "B"]))
        (is (contains? pairs ["A" "C"]))
        (is (contains? pairs ["B" "C"]))))))

(deftest co-changed-files-rule
  (let [conn (make-conn)]
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
    (let [{:keys [ok]} (query/run-named-query (d/db conn) "co-changed-files")]
      (is (= 1 (count ok)) "One pair")
      (let [[p1 p2 cnt] (first ok)]
        (is (= #{"src/x.clj" "src/y.clj"} #{p1 p2}))
        (is (= 3 cnt))))))

(deftest top-contributors-query
  (let [conn (make-conn)]
    (d/transact conn {:tx-data [{:db/id "author-1"
                                 :person/email "a@test.com"
                                 :person/name "Alice"}
                                {:git/sha "sha-1" :git/type :commit
                                 :commit/message "one"
                                 :commit/author "author-1"}
                                {:git/sha "sha-2" :git/type :commit
                                 :commit/message "two"
                                 :commit/author "author-1"}]})
    (let [{:keys [ok]} (query/run-named-query (d/db conn) "top-contributors")]
      (is (= 1 (count ok)))
      (let [[name email cnt] (first ok)]
        (is (= "Alice" name))
        (is (= "a@test.com" email))
        (is (= 2 cnt))))))
