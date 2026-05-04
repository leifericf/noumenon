(ns noumenon.p4
  "Adapter from Noumenon to clj-p4. The single point of integration with
   the pure-Clojure Perforce-to-Git library; everywhere else in the code
   talks to this namespace, not to clj-p4 directly.

   Connection details (`P4PORT`, `P4USER`, `P4CLIENT`, `P4PASSWD`,
   `P4CHARSET`) come from the environment, just like the historical
   git-p4 wrapper. Clones are bare; sync derives the stream from the most
   recent commit's `git-p4:` trailer."
  (:require [clj-p4.api :as api]
            [clj-p4.exclude :as p4-exclude]
            [clj-p4.spec :as p4-spec]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.util :refer [log!]]))

(def ^:private excludes-resource
  (delay (some-> (io/resource "p4-excludes.edn") slurp edn/read-string)))

(defn depot-path?
  "True if `s` looks like a Perforce depot path."
  [s]
  (p4-spec/depot-path? s))

(defn validate-depot-path!
  "Throws on invalid depot paths; returns the path otherwise."
  [s]
  (p4-spec/validate-depot-path! s))

(defn available?
  "True if the `p4` CLI is on PATH."
  []
  (api/available?))

(defn clone?
  "True if `repo-path` is a clj-p4 (or legacy git-p4) clone — has a commit
   on `refs/heads/main` whose message contains the `git-p4:` trailer."
  [repo-path]
  (api/clone? repo-path))

(defn depot->clone-name
  "Derive a local clone directory name from a depot path.
     //depot/ProjectA/main/... → ProjectA-main
     //stream/main/...         → main"
  [depot-path]
  (let [{:depot/keys [depot segments]} (p4-spec/parse-depot-path depot-path)
        parts (if (seq segments) segments [depot])]
    (-> (str/join "-" parts)
        (str/replace #"[^a-zA-Z0-9\-_.]" ""))))

(defn clone-path
  "Local clone path for a depot path: `data/repos/<derived-name>`."
  [depot-path]
  (str "data/repos/" (depot->clone-name depot-path)))

(defn- conn-from-env []
  (cond-> {:p4/port (or (System/getenv "P4PORT") "perforce:1666")}
    (System/getenv "P4USER")    (assoc :p4/user    (System/getenv "P4USER"))
    (System/getenv "P4CLIENT")  (assoc :p4/client  (System/getenv "P4CLIENT"))
    (System/getenv "P4CHARSET") (assoc :p4/charset (keyword (System/getenv "P4CHARSET")))
    (System/getenv "P4PASSWD")  (assoc :p4/ticket  (System/getenv "P4PASSWD"))))

(defn- progress-fn [op]
  (when (= :process-change (:op/kind op))
    (log! (str "p4 change " (:op/change op)))))

(defn- compile-excludes [opts]
  (let [patterns (p4-exclude/exclude-patterns
                  (assoc opts :resource @excludes-resource))]
    (p4-exclude/compile-patterns patterns)))

(defn clone!
  "Clone a Perforce stream into `target-dir` as a bare git repo. Options:
     :no-default-excludes?  skip the `p4-excludes.edn` resource defaults.
     :extra-excludes        additional patterns to exclude.
     :includes              patterns to remove from the union (whitelist).
     :max-changes           cap on imported changelists."
  [depot-path target-dir opts]
  (validate-depot-path! depot-path)
  (log! (str "clj-p4: cloning " depot-path " into " target-dir))
  (api/clone! {:conn        (conn-from-env)
               :stream      depot-path
               :target      (str target-dir)
               :exclude     (compile-excludes (or opts {}))
               :max-changes (:max-changes opts)
               :progress-fn progress-fn})
  (log! "clj-p4: clone complete")
  (str target-dir))

(defn- last-commit-message [repo-path]
  (try
    (-> (clj-p4.shell.proc/run-checked!
         ["git" "-C" (str repo-path) "log" "-1" "--pretty=%B"
          "refs/heads/main"])
        :stdout-bytes
        (String. "UTF-8"))
    (catch Exception _ nil)))

(defn- stream-from-trailer
  "Extract the stream depot-path from a `git-p4:` commit trailer."
  [msg]
  (when msg
    (when-let [[_ s] (re-find #"depot-paths\s*=\s*\"([^\"]+?)/?\"" msg)]
      s)))

(defn sync!
  "Bring an existing clj-p4 clone at `repo-path` up to date with Perforce.
   Derives the stream from the most recent commit's `git-p4:` trailer."
  [repo-path]
  (let [stream (-> (last-commit-message repo-path) stream-from-trailer)]
    (when-not stream
      (throw (ex-info (str "Cannot determine Perforce stream for "
                           repo-path " — re-clone needed")
                      {:repo-path (str repo-path)})))
    (log! (str "clj-p4: syncing " repo-path " from " stream))
    (api/sync! {:conn        (conn-from-env)
                :stream      stream
                :target      (str repo-path)
                :progress-fn progress-fn})
    (log! "clj-p4: sync complete")
    true))
