(ns noumenon.main
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.db :as db]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.query :as query]))

;; --- Helpers ---

(defn derive-db-name
  "Extract database name from a repo path: last path component, trailing slashes stripped."
  [repo-path]
  (-> repo-path str (str/replace #"/+$" "") (str/split #"/") last))

(defn resolve-db-dir
  "Resolve the database storage directory. Defaults to data/datomic/ relative to cwd."
  [opts]
  (or (:db-dir opts)
      (str (.getAbsolutePath (io/file "data" "datomic")))))

(defn- parse-args
  "Parse CLI args into {:subcommand s, :repo-path p, :db-dir d} or {:error keyword}."
  [args]
  (if (empty? args)
    {:error :no-args}
    (let [[sub & rest-args] args]
      (if-not (#{"import" "status" "analyze" "query"} sub)
        {:error :unknown-subcommand :subcommand sub}
        (loop [remaining rest-args
               opts {}
               positional []]
          (cond
            (empty? remaining)
            (if (= "query" sub)
              (cond
                (< (count positional) 2)
                {:error :query-missing-args}
                :else
                (assoc opts :subcommand sub
                       :query-name (first positional)
                       :repo-path (second positional)))
              (if (seq positional)
                (assoc opts :subcommand sub :repo-path (first positional))
                {:error :no-repo-path :subcommand sub}))

            (= "--db-dir" (first remaining))
            (if (second remaining)
              (recur (drop 2 remaining) (assoc opts :db-dir (second remaining)) positional)
              {:error :missing-db-dir-value})

            (str/starts-with? (first remaining) "-")
            {:error :unknown-flag :flag (first remaining)}

            :else
            (recur (rest remaining) opts (conj positional (first remaining)))))))))

(def ^:private usage-text
  (str/join "\n"
            ["Usage: clj -M:run <subcommand> [options] <repo-path>"
             ""
             "Subcommands:"
             "  import   Import git history and file structure into Datomic"
             "  analyze  Enrich imported files with LLM-driven semantic analysis"
             "  query    Run a named Datalog query against the knowledge graph"
             "  status   Show import counts for a repository"
             ""
             "Options:"
             "  --db-dir <dir>  Override default storage directory (default: data/datomic/)"]))

(defn- print-usage! []
  (binding [*out* *err*]
    (println usage-text)))

(defn- print-error! [msg]
  (binding [*out* *err*]
    (println (str "Error: " msg))))

;; --- Subcommands ---

(defn- validate-repo-path [repo-path]
  (let [f (io/file repo-path)]
    (cond
      (not (.exists f))
      (str "Path does not exist: " repo-path)

      (not (.isDirectory f))
      (str "Path is not a directory: " repo-path)

      (not (.exists (io/file f ".git")))
      (str "Path is not a git repository: " repo-path))))

(defn- db-exists? [db-dir db-name]
  (.exists (io/file db-dir "noumenon" db-name)))

(defn do-import
  "Run the import subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (let [db-dir  (resolve-db-dir opts)
          db-name (derive-db-name repo-path)
          conn    (db/connect-and-ensure-schema db-dir db-name)
          git-r   (git/import-commits! conn repo-path)
          files-r (files/import-files! conn repo-path)
          db-path (.getAbsolutePath (io/file db-dir "noumenon" db-name))]
      {:exit   0
       :result (merge (select-keys git-r [:commits-imported :commits-skipped])
                      (select-keys files-r [:files-imported :files-skipped :dirs-imported])
                      {:db-path db-path})})))

(defn do-analyze
  "Run the analyze subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (let [db-dir  (resolve-db-dir opts)
          db-name (derive-db-name repo-path)]
      (if-not (db-exists? db-dir db-name)
        (do (print-error! (str "No database found for \"" db-name
                               "\". Run `import` first."))
            {:exit 1})
        (let [conn   (db/connect-and-ensure-schema db-dir db-name)
              result (analyze/analyze-repo! conn repo-path
                                            analyze/invoke-claude-cli)]
          {:exit   0
           :result result})))))

(defn do-query
  "Run the query subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path query-name] :as opts}]
  (if-let [err (validate-repo-path repo-path)]
    (do (print-error! err) {:exit 1})
    (let [db-dir  (resolve-db-dir opts)
          db-name (derive-db-name repo-path)]
      (if-not (db-exists? db-dir db-name)
        (do (print-error! (str "No database found for \"" db-name
                               "\". Run `import` first."))
            {:exit 1})
        (let [conn (db/connect-and-ensure-schema db-dir db-name)
              db   (d/db conn)
              {:keys [ok error]} (query/run-named-query db query-name)]
          (if error
            (do (print-error! error) {:exit 1})
            {:exit 0 :result ok}))))))

(defn do-status
  "Run the status subcommand. Returns {:exit n :result map-or-nil}."
  [{:keys [repo-path] :as opts}]
  (let [db-dir  (resolve-db-dir opts)
        db-name (derive-db-name repo-path)]
    (if-not (db-exists? db-dir db-name)
      (do (print-error! (str "No database found for \"" db-name
                             "\". Run `import` first."))
          {:exit 1})
      (let [conn    (db/connect-and-ensure-schema db-dir db-name)
            db      (d/db conn)
            commits (count (d/q '[:find ?e :where [?e :git/type :commit]] db))
            files   (count (d/q '[:find ?e :where [?e :file/path _] [?e :file/size _]] db))
            dirs    (count (d/q '[:find ?e :where [?e :dir/path _]] db))
            db-path (.getAbsolutePath (io/file db-dir "noumenon" db-name))]
        {:exit   0
         :result {:commits commits
                  :files   files
                  :dirs    dirs
                  :db-path db-path}}))))

;; --- Entry point ---

(defn run
  "Main dispatch. Returns {:exit n :result map-or-nil}.
   Prints errors/usage to stderr, result EDN to stdout."
  [args]
  (let [parsed (parse-args args)]
    (case (:error parsed)
      :no-args
      (do (print-usage!) {:exit 1})

      :unknown-subcommand
      (do (print-error! (str "Unknown subcommand: " (:subcommand parsed)))
          (print-usage!)
          {:exit 1})

      :no-repo-path
      (do (print-error! "Missing <repo-path> argument.")
          (print-usage!)
          {:exit 1})

      :query-missing-args
      (do (print-error! "Usage: query <query-name> <repo-path>")
          {:exit 1})

      :missing-db-dir-value
      (do (print-error! "Missing value for --db-dir.")
          (print-usage!)
          {:exit 1})

      :unknown-flag
      (do (print-error! (str "Unknown option: " (:flag parsed)))
          (print-usage!)
          {:exit 1})

      ;; no error — dispatch subcommand
      (let [result (case (:subcommand parsed)
                     "import"  (do-import parsed)
                     "analyze" (do-analyze parsed)
                     "query"   (do-query parsed)
                     "status"  (do-status parsed))]
        (when (:result result)
          (prn (:result result)))
        result))))

(defn -main [& args]
  (let [{:keys [exit]} (run args)]
    (System/exit exit)))
