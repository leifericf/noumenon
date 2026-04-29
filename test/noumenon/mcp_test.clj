(ns noumenon.mcp-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [noumenon.mcp :as mcp]))

(deftest repo-path->db-name-uses-local-derivation-for-directories
  (let [tmp-root (.toFile (java.nio.file.Files/createTempDirectory "noumenon-mcp-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo-dir  (io/file tmp-root "mino")]
    (.mkdir repo-dir)
    (is (= "mino" (#'mcp/repo-path->db-name (.getAbsolutePath repo-dir))))))

(deftest repo-path->db-name-passes-through-db-name-input
  (is (= "mino" (#'mcp/repo-path->db-name "mino"))))
