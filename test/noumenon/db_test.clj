(ns noumenon.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.db :as db]))

(deftest in-memory-database
  (let [client (db/create-client :mem)
        conn   (db/create-db client (str "mem-test-" (random-uuid)))]
    (testing "returns a valid connection"
      (is (some? conn)))
    (testing "database is queryable"
      (is (some? (d/db conn))))))

(deftest directory-backed-database
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                     "/noumenon-test-" (random-uuid))
        client  (db/create-client tmp-dir)
        conn    (db/create-db client "dir-test")]
    (testing "returns a valid connection"
      (is (some? conn)))
    (testing "database is queryable"
      (is (some? (d/db conn))))))

(deftest schema-on-connect
  (let [conn (db/connect-and-ensure-schema :mem (str "schema-connect-" (random-uuid)))
        db   (d/db conn)]
    (testing "schema is present without separate transact call"
      (is (some? (d/pull db '[:db/ident] :git/sha)))
      (is (some? (d/pull db '[:db/ident] :file/path)))
      (is (some? (d/pull db '[:db/ident] :code/name)))
      (is (some? (d/pull db '[:db/ident] :prov/confidence))))))
