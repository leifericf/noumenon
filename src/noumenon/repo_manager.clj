(ns noumenon.repo-manager
  "Server-managed repository lifecycle for headless/shared deployments.
   Manages bare git clones, registration, and refresh scheduling."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.git :as git]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]]))

;; --- Repo directory layout ---
;; Bare clones live under <db-dir>/repos/<db-name>.git/

(defn repos-dir
  "Base directory for server-managed bare clones."
  [db-dir]
  (str (io/file db-dir "repos")))

(defn repo-clone-path
  "Path to a bare clone for a given db-name."
  [db-dir db-name]
  (str (io/file (repos-dir db-dir) (str db-name ".git"))))

;; --- DB name derivation from URL ---

(defn url->db-name
  "Derive a database name from a Git URL or Perforce depot path.
   https://github.com/ring-clojure/ring.git -> ring-clojure-ring
   git@github.com:anthropics/claude-code.git -> anthropics-claude-code
   //depot/ProjectA/main/... -> ProjectA-main"
  [url]
  (let [name (if (git/p4-depot-path? url)
               (git/p4-depot->clone-name url)
               (let [cleaned (-> url (str/replace #"\.git$" "") (str/replace #"/$" ""))
                     parts   (str/split cleaned #"[/:]")
                     segments (take-last 2 parts)
                     raw     (str/join "-" segments)]
                 (str/replace raw #"[^a-zA-Z0-9\-_.]" "")))]
    (when (str/blank? name)
      (throw (ex-info "Cannot derive db-name from URL" {:url url})))
    name))

;; --- Registration ---

(defn registered-repos
  "List all registered repos from the meta database.
   Returns [{:db-name str :url str :branch str :registered-at inst}]."
  [meta-db]
  (->> (d/q '[:find (pull ?r [:managed-repo/db-name :managed-repo/url
                              :managed-repo/branch :managed-repo/registered-at])
              :where [?r :managed-repo/db-name]]
            meta-db)
       (map first)
       (mapv (fn [r]
               {:db-name       (:managed-repo/db-name r)
                :url           (:managed-repo/url r)
                :branch        (or (:managed-repo/branch r) "main")
                :registered-at (:managed-repo/registered-at r)}))
       (sort-by :db-name)))

(defn register-repo!
  "Register a repo by URL or Perforce depot path. Clones, creates database, imports.
   Returns {:db-name str :clone-path str :import-result map}."
  [meta-conn db-dir {:keys [url name branch p4-opts]}]
  (let [db-name    (or name (url->db-name url))
        p4?        (git/p4-depot-path? url)
        clone-path (repo-clone-path db-dir db-name)
        clone-dir  (io/file clone-path)]
    ;; Prevent path traversal
    (when (str/includes? db-name "..")
      (throw (ex-info "Invalid db-name: contains '..'" {:db-name db-name})))
    ;; Clone if not already present
    (when-not (.isDirectory clone-dir)
      ;; Log the db-name only — earlier this echoed the absolute
      ;; clone-path, leaking the daemon's db-dir layout to anyone
      ;; with read access to the log.
      (log! (str "Cloning " url " into " db-name ".git ..."))
      (if p4?
        (git/p4-clone! url clone-path (or p4-opts {}))
        (git/clone-bare! url clone-path)))
    ;; Register in meta database
    (d/transact meta-conn
                {:tx-data [{:managed-repo/db-name       db-name
                            :managed-repo/url            url
                            :managed-repo/branch         (or branch "main")
                            :managed-repo/registered-at  (java.util.Date.)}]})
    ;; Import into knowledge graph
    (let [conn   (db/get-or-create-conn db-dir db-name)
          result (git/import-commits! conn clone-path url)]
      {:db-name    db-name
       :clone-path clone-path
       :import     result})))

(defn refresh-repo!
  "Fetch latest and run incremental import + enrich for a registered repo.
   Detects git-p4 clones and uses p4-sync! instead of git fetch.
   Returns summary map."
  [db-dir db-name]
  (let [clone-path (repo-clone-path db-dir db-name)
        conn       (db/get-or-create-conn db-dir db-name)]
    (when-not (.isDirectory (io/file clone-path))
      ;; Reference db-name in the message; absolute clone-path stays
      ;; in :ex-data for daemon-side debugging but isn't logged.
      (throw (ex-info (str "Clone not found for " db-name)
                      {:db-name db-name :clone-path clone-path})))
    (log! (str "Fetching " db-name " ..."))
    (if (git/p4-clone? clone-path)
      (git/p4-sync! clone-path)
      (git/fetch! clone-path))
    (let [repo-uri (or (ffirst (d/q '[:find ?uri :where [_ :repo/uri ?uri]]
                                    (d/db conn)))
                       (str "managed://" db-name))]
      (sync/update-repo! conn clone-path repo-uri {}))))

(defn remove-repo!
  "Remove a registered repo: delete clone, delete database, evict cache."
  [meta-conn db-dir db-name]
  (let [clone-path (repo-clone-path db-dir db-name)
        clone-dir  (io/file clone-path)]
    ;; Remove from meta database
    (let [meta-db (d/db meta-conn)
          eid     (ffirst (d/q '[:find ?r :in $ ?n
                                 :where [?r :managed-repo/db-name ?n]]
                               meta-db db-name))]
      (when eid
        (d/transact meta-conn {:tx-data [[:db/retractEntity eid]]})))
    ;; Delete bare clone
    (when (.isDirectory clone-dir)
      (log! (str "Removing clone for " db-name))
      (doseq [f (reverse (file-seq clone-dir))]
        (.delete f)))
    ;; Delete database
    (try
      (let [client (db/create-client db-dir)]
        (db/delete-db client db-name))
      (catch Exception e
        (log! (str "Warning: could not delete database " db-name ": " (.getMessage e)))))
    ;; Evict from connection cache
    (db/evict-conn! db-dir db-name)
    (log! (str "Removed repo " db-name))
    true))
