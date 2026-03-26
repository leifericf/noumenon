(ns test-pipeline
  "End-to-end pipeline test: import → analyze → postprocess on OSS repos per language.

   Usage from REPL:  (test-pipeline/run-all!)
                     (test-pipeline/run-all! {:postprocess-only true})
                     (test-pipeline/run-one! :clojure)

   Usage from CLI:   clj -M:dev -m test-pipeline
                     clj -M:dev -m test-pipeline --postprocess-only"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.main :as main]
            [noumenon.query :as query]
            [noumenon.util :refer [log!]]))

;; ---------------------------------------------------------------------------
;; Test repos — one per language
;; ---------------------------------------------------------------------------

(def test-repos
  [{:lang :clojure    :url "https://github.com/ring-clojure/ring"}
   {:lang :python     :url "https://github.com/pallets/flask"}
   {:lang :javascript :url "https://github.com/expressjs/express"}
   {:lang :typescript :url "https://github.com/denoland/fresh"}
   {:lang :c          :url "https://github.com/redis/redis"}
   {:lang :go         :url "https://github.com/junegunn/fzf"}
   {:lang :rust       :url "https://github.com/BurntSushi/ripgrep"}
   {:lang :java       :url "https://github.com/google/guava"}])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- repo-name [url]
  (-> url (str/split #"/") last (str/replace #"\.git$" "")))

(defn- repo-local-path [url]
  (str "data/repos/" (repo-name url)))

(defn- db-dir []
  (str (.getAbsolutePath (io/file "data" "datomic"))))

(defn- repo-imported? [url]
  (let [name (repo-name url)]
    (.exists (io/file (db-dir) "noumenon" name))))

(defn- repo-analyzed? [url]
  (when (repo-imported? url)
    (let [conn (db/connect-and-ensure-schema (db-dir) (repo-name url))
          db   (d/db conn)
          n    (ffirst (d/q '[:find (count ?e) :where [?e :sem/summary _]] db))]
      (and n (pos? n)))))

;; ---------------------------------------------------------------------------
;; Pipeline steps
;; ---------------------------------------------------------------------------

(defn- run-import! [{:keys [url]}]
  (log! (str "  Importing " url " ..."))
  (main/do-import {:repo-path url}))

(defn- run-analyze! [{:keys [url]}]
  (log! (str "  Analyzing " (repo-name url) " ..."))
  (main/do-analyze {:repo-path (repo-local-path url)
                    :provider "glm"
                    :model "sonnet"}))

(defn- run-postprocess! [{:keys [url]}]
  (log! (str "  Postprocessing " (repo-name url) " ..."))
  (main/do-postprocess {:repo-path (repo-local-path url)}))

;; ---------------------------------------------------------------------------
;; Validation queries
;; ---------------------------------------------------------------------------

(defn- validate-repo [{:keys [url lang]}]
  (let [conn (db/connect-and-ensure-schema (db-dir) (repo-name url))
        db   (d/db conn)
        import-count (or (ffirst (d/q '[:find (count ?f)
                                        :where [?f :file/imports _]] db)) 0)
        top-hotspots (->> (d/q '[:find ?path (count ?imp)
                                 :where [?f :file/path ?path]
                                 [?imp :file/imports ?f]] db)
                          (sort-by second >)
                          (take 3))
        circular     (d/q '[:find ?a ?b
                            :where [?fa :file/imports ?fb]
                            [?fb :file/imports ?fa]
                            [?fa :file/path ?a]
                            [?fb :file/path ?b]
                            [(!= ?fa ?fb)]
                            [(< ?a ?b)]] db)]
    {:lang           lang
     :repo           (repo-name url)
     :files-with-imports import-count
     :top-hotspots   (vec top-hotspots)
     :circular-count (count circular)
     :pass?          (pos? import-count)}))

;; ---------------------------------------------------------------------------
;; Orchestration
;; ---------------------------------------------------------------------------

(defn run-one!
  "Run the full pipeline on a single test repo by language keyword."
  ([lang] (run-one! lang {}))
  ([lang {:keys [postprocess-only]}]
   (let [{:keys [url] :as repo} (first (filter #(= lang (:lang %)) test-repos))]
     (when-not repo
       (throw (ex-info (str "No test repo for language: " lang) {:lang lang})))
     (log! (str "\n=== " (str/upper-case (name lang)) ": " (repo-name url) " ==="))
     (when-not postprocess-only
       (when-not (repo-imported? url)
         (run-import! repo))
       (when-not (repo-analyzed? url)
         (run-analyze! repo)))
     (run-postprocess! repo)
     (let [result (validate-repo repo)]
       (log! (str "  Result: " (if (:pass? result) "PASS" "FAIL")
                  " — " (:files-with-imports result) " files with imports"))
       result))))

(defn run-all!
  "Run the full pipeline on all test repos. Returns summary table."
  ([] (run-all! {}))
  ([opts]
   (log! "\n╔══════════════════════════════════════╗")
   (log!   "║  Noumenon E2E Pipeline Test           ║")
   (log!   "╚══════════════════════════════════════╝")
   (let [results (mapv #(try (run-one! (:lang %) opts)
                             (catch Exception e
                               {:lang (:lang %) :repo (repo-name (:url %))
                                :pass? false :error (.getMessage e)}))
                       test-repos)]
     (log! "\n=== SUMMARY ===")
     (log! (format "%-12s %-15s %-8s %s" "Language" "Repo" "Status" "Imports"))
     (log! (str/join "" (repeat 55 "─")))
     (doseq [{:keys [lang repo pass? files-with-imports error]} results]
       (log! (format "%-12s %-15s %-8s %s"
                     (name lang) repo
                     (if pass? "PASS" "FAIL")
                     (or files-with-imports (str "error: " error)))))
     (let [passed (count (filter :pass? results))]
       (log! (str "\n" passed "/" (count results) " languages passed"))
       results))))

;; ---------------------------------------------------------------------------
;; CLI entry point
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (let [opts (when (some #{"--postprocess-only"} args)
               {:postprocess-only true})]
    (run-all! (or opts {}))
    (System/exit 0)))
