(ns noumenon.repo
  "Unified repository identifier resolution.
   Accepts filesystem paths, Git URLs, or database names."
  (:require [clojure.java.io :as io]
            [noumenon.git :as git]
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

(defn resolve-repo
  "Resolve a repo identifier (path, Git URL, or database name) to a context map.
   Returns {:repo-path <string> :db-name <string>}.
   Options:
     :lookup-uri-fn  — (fn [db-dir db-name]) → stored :repo/uri or nil
     :db-dir         — database storage directory"
  [identifier db-dir {:keys [lookup-uri-fn]}]
  (cond
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
