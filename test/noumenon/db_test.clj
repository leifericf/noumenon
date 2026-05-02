(ns noumenon.db-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.db :as db]))

(defn- delete-tree! [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-tree! (.listFiles f)))
  (.delete f))

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

(deftest connect-recovers-from-stale-catalog-entry
  (testing "When Datomic-Local's system catalog still has a DB entry but the
            on-disk directory has been removed externally (e.g. `bb
            prune-deltas` wiping a delta dir, or the user deleting it
            manually while the daemon is down), `create-database` is a
            no-op against the catalog and `connect` throws the anomaly
            `:cognitect.anomalies/not-found`. `connect-and-ensure-schema`
            recovers by clearing the stale catalog entry and recreating
            the DB so the next caller gets a working connection instead
            of a 500 error."
    (let [tmp     (str (System/getProperty "java.io.tmpdir")
                       "/noumenon-test-" (random-uuid))
          db-name (str "stale-catalog-" (random-uuid))]
      ;; Phase 1: populate the catalog by creating + using once.
      (db/connect-and-ensure-schema tmp db-name)
      ;; Phase 2: external deletion of the on-disk directory while the
      ;; catalog still has the entry (and our cache may also have it).
      (db/evict-conn! tmp db-name)
      (delete-tree! (io/file tmp "noumenon" db-name))
      (is (not (.exists (io/file tmp "noumenon" db-name)))
          "precondition: dir is gone")
      ;; Phase 3: re-connect should recover, not throw not-found.
      (let [conn (db/connect-and-ensure-schema tmp db-name)
            db   (d/db conn)]
        (is (some? conn) "recovery returned a working connection")
        (is (some? (d/pull db '[:db/ident] :git/sha))
            "recovered DB has schema reapplied")))))
