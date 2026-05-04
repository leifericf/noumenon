(ns noumenon.repo
  "Unified repository identifier resolution.
   Accepts filesystem paths, Git URLs, Perforce depot paths, or database names."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.git :as git]
            [noumenon.p4 :as p4]
            [noumenon.util :as util :refer [log!]]))

(defn- clone-or-reuse
  "Clone a Git URL to data/repos/<name>/, or reuse an existing clone."
  [url]
  (let [name   (git/url->repo-name url)
        target (str "data/repos/" name)]
    (if (.isDirectory (io/file target ".git"))
      (do (log! (str "Using existing clone at " target)) target)
      (do (log! (str "Cloning " url " into " target " ..."))
          (git/clone! url target)
          target))))

(defn- try-as-path
  "If identifier is a valid repo path, return its canonical form. Else nil."
  [identifier]
  (let [f (io/file identifier)]
    (when (and (.exists f) (nil? (util/validate-repo-path (.getCanonicalPath f))))
      (.getCanonicalPath f))))

(defn- clone-or-reuse-p4
  "Clone a Perforce stream via clj-p4 as a bare repo, or reuse the existing
   clone. Bare-repo presence is detected by the `HEAD` file at the target."
  [depot-path opts]
  (let [target (p4/clone-path depot-path)]
    (if (.isFile (io/file target "HEAD"))
      (do (log! (str "Using existing clj-p4 clone at " target)) target)
      (do (log! (str "Cloning P4 stream " depot-path " into " target " ..."))
          (p4/clone! depot-path target opts)
          target))))

(defn resolve-repo
  "Resolve a repo identifier (path, Git URL, Perforce depot path, or database name)
   to a context map. Returns {:repo-path <string> :db-name <string>}.
   Options:
     :lookup-uri-fn  — (fn [db-dir db-name]) → stored :repo/uri or nil
     :db-dir         — database storage directory
     :p4-opts        — options for clj-p4 clone (excludes, max-changes, etc.)"
  [identifier db-dir {:keys [lookup-uri-fn p4-opts]}]
  (cond
    ;; Perforce depot path — clone via clj-p4 and use local path
    (p4/depot-path? identifier)
    (let [local (clone-or-reuse-p4 identifier (or p4-opts {}))]
      {:repo-path (.getCanonicalPath (io/file local))
       :db-name   (util/derive-db-name local)})

    ;; Git URL — clone and use local path
    (git/git-url? identifier)
    (let [local (clone-or-reuse identifier)]
      {:repo-path (.getCanonicalPath (io/file local))
       :db-name   (util/derive-db-name local)})

    ;; Valid filesystem path with .git
    (try-as-path identifier)
    (let [canonical (try-as-path identifier)]
      {:repo-path canonical
       :db-name   (util/derive-db-name canonical)})

    ;; Looks like a filesystem path but invalid — fail with specific reason
    (or (.isAbsolute (io/file identifier))
        (.exists (io/file identifier)))
    (let [reason (or (util/validate-repo-path identifier) "invalid path")]
      (throw (ex-info (str "Path " reason ": " identifier)
                      {:identifier identifier :reason reason})))

    ;; Try as database name
    :else
    (let [db-name identifier
          uri     (when lookup-uri-fn (lookup-uri-fn db-dir db-name))]
      (if uri
        {:repo-path uri :db-name db-name}
        ;; Check if DB directory exists even without stored URI
        (let [db-path (io/file db-dir "noumenon" db-name)]
          (if (.isDirectory db-path)
            {:repo-path (str "db://" db-name) :db-name db-name}
            (throw (ex-info (str "Repository not found: " identifier
                                 ". Use a filesystem path, Git URL, or database name.")
                            {:identifier identifier}))))))))

(defn resolve-extra-repos
  "Resolve a comma-separated string of repo identifiers into
   `[{:db <datomic-db> :repo-name <db-name>} …]`. Used by introspect's
   multi-repo evaluation path on every transport so a single piece of
   logic decides how to open extra-repo handles. Returns nil for
   nil/blank input. Does not validate length on individual identifiers
   — callers that need that should run the cap before calling."
  ([extra-repos-str db-dir]
   (resolve-extra-repos extra-repos-str db-dir nil))
  ([extra-repos-str db-dir lookup-uri-fn]
   (when (and extra-repos-str (not (str/blank? extra-repos-str)))
     (->> (str/split extra-repos-str #",")
          (mapv (fn [raw]
                  (let [raw     (str/trim raw)
                        {:keys [db-name]}
                        (resolve-repo raw db-dir
                                      (cond-> {} lookup-uri-fn (assoc :lookup-uri-fn lookup-uri-fn)))
                        conn    (db/get-or-create-conn db-dir db-name)]
                    {:db (d/db conn) :repo-name db-name})))))))
