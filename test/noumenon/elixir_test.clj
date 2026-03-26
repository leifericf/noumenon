(ns noumenon.elixir-test
  "End-to-end validation of the import pipeline on the Jason (Elixir) repository.
   Clones Jason if not already present, imports and postprocesses via the CLI,
   and verifies Elixir-specific import extraction."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.main :as main]))

;; --- Test infrastructure ---

(def ^:private jason-url "https://github.com/michalmuskala/jason.git")

(def ^:private jason-dir
  (str (.getAbsolutePath (io/file "data" "repos" "jason"))))

(def ^:private db-dir
  (str (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")))
       "/noumenon-jason-test-" (random-uuid)))

(defn- elixir-available? []
  (try
    (zero? (:exit (shell/sh "which" "elixir")))
    (catch Exception _ false)))

(defn- ensure-jason-clone! []
  (when-not (.exists (io/file jason-dir ".git"))
    (.mkdirs (io/file jason-dir))
    (let [{:keys [exit err]} (shell/sh "git" "clone" "--no-checkout" jason-url jason-dir)]
      (when (not= 0 exit)
        (throw (ex-info (str "Failed to clone Jason: " err) {:exit exit}))))
    (let [{:keys [exit err]} (shell/sh "git" "-C" jason-dir "checkout" "HEAD")]
      (when (not= 0 exit)
        (throw (ex-info (str "Failed to checkout Jason: " err) {:exit exit}))))))

(defn- run-capturing [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (binding [*out* out *err* err]
      (let [result (main/run args)]
        (assoc result :stdout (str out) :stderr (str err))))))

(use-fixtures :once
  (fn [f]
    (when (elixir-available?)
      (ensure-jason-clone!)
      (f))))

;; --- Tests ---

(deftest jason-import-test
  (when (elixir-available?)
    (let [result (run-capturing ["import" "--db-dir" db-dir jason-dir])]
      (testing "import succeeds"
        (is (= 0 (:exit result))))
      (testing "stdout contains import counts"
        (is (str/includes? (:stdout result) ":files-imported"))))))

(deftest jason-postprocess-test
  (when (elixir-available?)
    (run-capturing ["import" "--db-dir" db-dir jason-dir])
    (let [result (run-capturing ["postprocess" "--db-dir" db-dir jason-dir])]
      (testing "postprocess succeeds"
        (is (= 0 (:exit result))))
      (testing "import edges resolved"
        (is (str/includes? (:stderr result) "import edges resolved"))))))

(deftest jason-spot-check-elixir-file
  (when (elixir-available?)
    (run-capturing ["import" "--db-dir" db-dir jason-dir])
    (let [conn   (db/connect-and-ensure-schema db-dir "jason")
          db     (d/db conn)
          entity (d/pull db '[:file/path :file/ext :file/lang]
                         [:file/path "lib/jason.ex"])]
      (testing "known Elixir file has correct attributes"
        (is (= "lib/jason.ex" (:file/path entity)))
        (is (= "ex" (:file/ext entity)))
        (is (= :elixir (:file/lang entity)))))))

(deftest jason-import-edges-exist
  (when (elixir-available?)
    (run-capturing ["import" "--db-dir" db-dir jason-dir])
    (run-capturing ["postprocess" "--db-dir" db-dir jason-dir])
    (let [conn (db/connect-and-ensure-schema db-dir "jason")
          db   (d/db conn)
          edges (d/q '[:find (count ?f)
                       :where [?f :file/imports _]]
                     db)]
      (testing "at least one file has import edges"
        (is (pos? (ffirst edges)))))))
