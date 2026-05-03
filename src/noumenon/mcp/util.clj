(ns noumenon.mcp.util
  "Shared infrastructure for MCP tool handlers — connection setup, input
   validation, selector parsing, and small re-exports of protocol-level
   helpers so cluster namespaces don't have to require both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.db :as db]
            [noumenon.mcp.protocol :as protocol]
            [noumenon.repo :as repo]
            [noumenon.sync :as sync]
            [noumenon.util :as util :refer [log!]]))

;; --- Re-exports ---

(def tool-result protocol/tool-result)
(def tool-error  protocol/tool-error)

;; --- Length caps (MCP-specific; util/* covers the cross-layer caps) ---

(def max-repo-path-len util/max-repo-path-len)
(def max-question-len  util/max-question-len)
(def max-model-len 256)
(def max-provider-len 64)
(def max-layers-len 64)
(def max-run-id-len 256)

(def allowed-layers
  #{:raw :import :enrich :full :embedded})

(def allowed-introspect-targets
  #{:examples :system-prompt :rules :code :train})

;; --- Validation ---

(defn validate-llm-inputs!
  "Validate model and provider string lengths when present."
  [args]
  (when-let [m (args "model")] (util/validate-string-length! "model" m max-model-len))
  (when-let [p (args "provider")] (util/validate-string-length! "provider" p max-provider-len)))

(defn provider+model
  "Resolve {:provider :model} from MCP args, falling back to defaults.
   Optional `extra` is merged in last (e.g. `{:max-tokens 16384}`)."
  ([args defaults] (provider+model args defaults nil))
  ([args defaults extra]
   (merge {:provider (or (args "provider") (:provider defaults))
           :model    (or (args "model") (:model defaults))}
          extra)))

(defn validate-layers
  "Parse and validate a comma-separated layers string. Returns keyword vector or nil."
  [layers-str]
  (when layers-str
    (util/validate-string-length! "layers" layers-str max-layers-len)
    (let [kws (mapv keyword (str/split layers-str #","))]
      (when-let [bad (seq (remove allowed-layers kws))]
        (throw (ex-info (str "Unknown layers: " (pr-str bad)
                             ". Valid: raw, import, enrich, full")
                        {:user-message (str "Unknown layers: " (pr-str bad)
                                            ". Valid: raw, import, enrich, full")})))
      kws)))

(defn selector-opts
  "Pull file-selector flags out of MCP args, dropping nils."
  [args]
  (let [opts {:path    (args "path")
              :include (args "include")
              :exclude (args "exclude")
              :lang    (args "lang")}]
    (reduce-kv (fn [m k v] (cond-> m v (assoc k v))) {} opts)))

;; --- Connection / repo resolution ---

(defn get-or-create-conn
  "Delegate to shared db/conn-cache."
  [db-dir db-name]
  (db/get-or-create-conn db-dir db-name))

(defn lookup-repo-uri
  "Look up stored :repo/uri for a database name. Returns path string or nil."
  [db-dir db-name]
  (let [db-path (io/file db-dir "noumenon" db-name)]
    (when (.isDirectory db-path)
      (try
        (let [conn (get-or-create-conn db-dir db-name)
              db   (d/db conn)]
          (ffirst (d/q '[:find ?uri :where [_ :repo/uri ?uri]] db)))
        (catch Exception e
          (log! "lookup-repo-uri" db-name (.getMessage e))
          nil)))))

(defn resolve-extra-repos
  "Resolve comma-separated repo identifiers to [{:db db :repo-name name} ...]."
  [extra-repos-str db-dir]
  (when (seq extra-repos-str)
    (->> (str/split extra-repos-str #",")
         (mapv (fn [raw]
                 (let [raw     (str/trim raw)
                       {:keys [db-name]}
                       (repo/resolve-repo raw db-dir {:lookup-uri-fn lookup-repo-uri})
                       conn    (get-or-create-conn db-dir db-name)]
                   {:db (d/db conn) :repo-name db-name}))))))

(defn with-conn
  "Resolve repo identifier from arguments, get/create connection, call f with context.
   Accepts filesystem paths, Git URLs, or database names.
   When auto-update is enabled (default), transparently updates stale databases before returning."
  [args defaults f]
  (let [raw-id (args "repo_path")]
    (when-not (string? raw-id)
      (throw (ex-info "repo_path is required" {:field "repo_path"})))
    (util/validate-string-length! "repo_path" raw-id max-repo-path-len)
    (let [db-dir    (util/resolve-db-dir defaults)
          {:keys [repo-path db-name]}
          (repo/resolve-repo raw-id db-dir {:lookup-uri-fn lookup-repo-uri})
          conn      (get-or-create-conn db-dir db-name)
          meta-conn (db/ensure-meta-db db-dir)]
      (when (and (:auto-update defaults true)
                 (not (str/starts-with? (str repo-path) "db://")))
        (try
          (let [db (d/db conn)]
            (when (sync/stale? db repo-path)
              (log! "auto-update" "HEAD changed, updating...")
              (sync/update-repo! conn repo-path repo-path {:concurrency 8})))
          (catch Exception e
            (log! "auto-update" (str "failed, continuing: " (.getMessage e))))))
      (f {:conn conn :db (d/db conn)
          :meta-conn meta-conn :meta-db (d/db meta-conn)
          :repo-path repo-path :db-dir db-dir :db-name db-name}))))
