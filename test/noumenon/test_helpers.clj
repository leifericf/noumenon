(ns noumenon.test-helpers
  "Shared test utilities. Consolidates helpers that were duplicated
   across multiple test namespaces."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [noumenon.db :as db]
            [noumenon.main :as main]))

(defn make-test-conn
  "Create an in-memory Datomic connection with schema for testing.
   `prefix` identifies the test (e.g. \"files-test\")."
  [prefix]
  (db/connect-and-ensure-schema :mem (str prefix "-" (random-uuid))))

(defn run-capturing
  "Run main/run, capturing stdout and stderr as strings.
   Returns the result map with :stdout and :stderr added."
  [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (let [result (main/run args)]
        (assoc result :stdout (str out) :stderr (str err))))))

(defn ensure-git-clone!
  "Clone a git repo to dir if not already present. Checks out HEAD
   so that ls-tree works."
  [url dir]
  (when-not (.exists (io/file dir ".git"))
    (.mkdirs (io/file dir))
    (let [{:keys [exit err]} (shell/sh "git" "clone" "--no-checkout" url dir)]
      (when (not= 0 exit)
        (throw (ex-info (str "Failed to clone " url ": " err) {:exit exit}))))
    (let [{:keys [exit err]} (shell/sh "git" "-C" dir "checkout" "HEAD")]
      (when (not= 0 exit)
        (throw (ex-info (str "Failed to checkout in " dir ": " err) {:exit exit}))))))
