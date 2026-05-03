(ns noumenon.cli.util
  "Shared infrastructure for CLI command handlers — context construction,
   repo/db existence checks, and the with-valid-repo / with-existing-db
   wrappers that every command's body lives inside."
  (:require [clojure.java.io :as io]
            [datomic.client.api :as d]
            [noumenon.cli :as cli]
            [noumenon.db :as db]
            [noumenon.git :as git]
            [noumenon.llm :as llm]
            [noumenon.repo :as repo]
            [noumenon.util :as util :refer [log!]]))

(defn print-usage! []
  (log! (cli/format-global-help)))

(defn print-error! [msg]
  (log! (str "Error: " msg)))

(defn validate-repo-path [repo-path]
  (when-let [reason (util/validate-repo-path repo-path)]
    (str "Path " reason ": " repo-path)))

(defn db-exists? [db-dir db-name]
  (.exists (io/file db-dir "noumenon" db-name)))

(defn db-path
  [{:keys [db-dir db-name]}]
  (.getAbsolutePath (io/file db-dir "noumenon" db-name)))

(defn build-context
  [{:keys [repo-path] :as opts}]
  (let [db-name (util/derive-db-name repo-path)]
    (when-not db-name
      (throw (ex-info (str "Cannot derive database name from path: " repo-path)
                      {:repo-path repo-path})))
    {:repo-path repo-path
     :db-dir    (util/resolve-db-dir opts)
     :db-name   db-name}))

(defn missing-db-msg
  [{:keys [db-name repo-path]}]
  (str "No database found for \"" db-name "\". Run: "
       cli/program-name " import " (or repo-path "<repo-path>")))

(defn with-valid-repo
  [opts run!]
  (if-let [err (validate-repo-path (:repo-path opts))]
    (do (print-error! err) {:exit 1})
    (run! (build-context opts))))

(defn with-existing-db
  [ctx run!]
  (if-not (db-exists? (:db-dir ctx) (:db-name ctx))
    (do (print-error! (missing-db-msg ctx)) {:exit 1})
    (let [conn      (db/connect-and-ensure-schema (:db-dir ctx) (:db-name ctx))
          meta-conn (db/ensure-meta-db (:db-dir ctx))]
      (run! (assoc ctx
                   :conn conn :db (d/db conn)
                   :meta-conn meta-conn :meta-db (d/db meta-conn))))))

(defn resolve-repo-path
  "If repo-path is a Git URL, clone and return local path. Otherwise return as-is.
   Validation happens later in with-valid-repo."
  [repo-path]
  (if (git/git-url? repo-path)
    (:repo-path (repo/resolve-repo repo-path (util/resolve-db-dir {}) {}))
    repo-path))

(defn build-sync-opts
  [{:keys [analyze model provider concurrency path include exclude lang]}]
  (if analyze
    (let [{:keys [prompt-fn model-id]}
          (llm/wrap-as-prompt-fn-from-opts {:provider provider :model model})]
      {:concurrency         (or concurrency 8)
       :analyze-concurrency (or concurrency 3)
       :analyze?            true
       :model-id            model-id
       :invoke-llm          prompt-fn
       :path                path
       :include             include
       :exclude             exclude
       :lang                lang})
    {:concurrency         (or concurrency 8)
     :analyze-concurrency (or concurrency 3)
     :path                path
     :include             include
     :exclude             exclude
     :lang                lang}))
