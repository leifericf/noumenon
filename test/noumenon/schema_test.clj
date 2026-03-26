(ns noumenon.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.schema :as schema]))

(defn- fresh-conn
  "Create a fresh in-memory Datomic connection for testing."
  []
  (let [client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system      "test"})
        db-name (str "schema-test-" (random-uuid))]
    (d/create-database client {:db-name db-name})
    (d/connect client {:db-name db-name})))

(defn- schema-attr-count
  "Count user-defined schema attributes (excluding built-in Datomic attrs)."
  [conn]
  (->> (d/q '[:find ?e
              :where
              [?e :db/ident ?ident]
              [?e :db/valueType _]
              [(namespace ?ident) ?ns]
              (not [(clojure.string/starts-with? ?ns "db")])
              (not [(clojure.string/starts-with? ?ns "fressian")])]
            (d/db conn))
       count))

(deftest schema-transacts-into-fresh-db
  (let [conn (fresh-conn)]
    (schema/ensure-schema conn)
    (let [db (d/db conn)]
      (testing "core attributes present"
        (is (some? (d/pull db '[:db/ident] :git/sha)))
        (is (some? (d/pull db '[:db/ident] :file/path)))
        (is (some? (d/pull db '[:db/ident] :tx/source))))
      (testing "architecture attributes present"
        (is (some? (d/pull db '[:db/ident] :code/name))))
      (testing "provenance attributes present"
        (is (some? (d/pull db '[:db/ident] :prov/confidence)))))))

(deftest schema-transact-is-idempotent
  (let [conn (fresh-conn)]
    (schema/ensure-schema conn)
    (let [count-before (schema-attr-count conn)]
      (schema/ensure-schema conn)
      (is (= count-before (schema-attr-count conn))))))

(deftest missing-schema-file-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"not found"
                        (schema/load-schema "schema/nonexistent.edn"))))
