(ns noumenon.p4
  "Adapter from Noumenon to clj-p4. The single point of integration with
   the pure-Clojure Perforce-to-Git library; everywhere else in the code
   talks to this namespace, not to clj-p4 directly.

   Connection details (`P4PORT`, `P4USER`, `P4CLIENT`, `P4PASSWD`,
   `P4CHARSET`) come from the environment, just like the historical
   git-p4 wrapper. Clones are bare; sync derives the source from the
   most recent commit's `git-p4:` trailer.

   Binary filtering is delegated to clj-p4. Revisions Perforce itself
   classifies as binary (`:rev/type` ∈ `:binary`/`:apple`/`:resource`)
   are dropped, and clj-p4's built-in extension category set is applied
   on top via `:exclude-categories :all`."
  (:require [clj-p4.api :as api]
            [clj-p4.io.subprocess :as p4-proc]
            [clj-p4.predicates :as p4-pred]
            [clojure.string :as str]
            [noumenon.util :refer [log!]]))

(defn depot-path?
  "True if `s` looks like a Perforce depot path."
  [s]
  (p4-pred/depot-path? s))

(defn validate-depot-path!
  "Throws on invalid depot paths; returns the path otherwise."
  [s]
  (p4-pred/validate-depot-path! s))

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
  (let [{:depot/keys [depot segments]} (p4-pred/parse-depot-path depot-path)
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

(defn clone!
  "Clone a Perforce stream into `target-dir` as a bare git repo. Options:
     :max-changes  cap on imported changelists.

   Binary filtering is delegated to clj-p4 — see the namespace docstring."
  [depot-path target-dir opts]
  (validate-depot-path! depot-path)
  (log! (str "clj-p4: cloning " depot-path " into " target-dir))
  (api/clone! {:conn               (conn-from-env)
               :source             depot-path
               :target             (str target-dir)
               :exclude-binaries?  true
               :exclude-categories :all
               :max-changes        (:max-changes opts)
               :progress-fn        progress-fn})
  (log! "clj-p4: clone complete")
  (str target-dir))

(defn- last-commit-message [repo-path]
  (try
    (-> (p4-proc/run-checked!
         ["git" "-C" (str repo-path) "log" "-1" "--pretty=%B"
          "refs/heads/main"])
        :stdout-bytes
        (String. "UTF-8"))
    (catch Exception _ nil)))

(defn- stream-from-trailer
  "Extract the source depot-path from a `git-p4:` commit trailer."
  [msg]
  (when msg
    (when-let [[_ s] (re-find #"depot-paths\s*=\s*\"([^\"]+?)/?\"" msg)]
      s)))

(defn sync!
  "Bring an existing clj-p4 clone at `repo-path` up to date with Perforce.
   Derives the source from the most recent commit's `git-p4:` trailer.
   Same binary-filter policy as `clone!` — clj-p4 owns it."
  [repo-path]
  (let [source (-> (last-commit-message repo-path) stream-from-trailer)]
    (when-not source
      (throw (ex-info (str "Cannot determine Perforce source for "
                           repo-path " — re-clone needed")
                      {:repo-path (str repo-path)})))
    (log! (str "clj-p4: syncing " repo-path " from " source))
    (api/fetch! {:conn               (conn-from-env)
                 :source             source
                 :target             (str repo-path)
                 :exclude-binaries?  true
                 :exclude-categories :all
                 :progress-fn        progress-fn})
    (log! "clj-p4: sync complete")
    true))
