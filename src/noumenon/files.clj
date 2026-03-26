(ns noumenon.files
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]))

;; --- Extension → language mapping ---

(def ext->lang
  {"clj"    :clojure
   "cljs"   :clojurescript
   "cljc"   :clojure
   "edn"    :edn
   "java"   :java
   "py"     :python
   "js"     :javascript
   "ts"     :typescript
   "tsx"    :typescript
   "jsx"    :javascript
   "rb"     :ruby
   "rs"     :rust
   "go"     :go
   "c"      :c
   "h"      :c
   "cpp"    :cpp
   "hpp"    :cpp
   "cs"     :csharp
   "swift"  :swift
   "kt"     :kotlin
   "scala"  :scala
   "sh"     :shell
   "bash"   :shell
   "zsh"    :shell
   "sql"    :sql
   "html"   :html
   "css"    :css
   "scss"   :scss
   "json"   :json
   "yaml"   :yaml
   "yml"    :yaml
   "xml"    :xml
   "md"     :markdown
   "toml"   :toml
   "lua"    :lua
   "ex"     :elixir
   "exs"    :elixir
   "hs"     :haskell
   "ml"     :ocaml
   "r"      :r
   "pl"     :perl
   "php"    :php
   "tf"     :terraform
   "proto"  :protobuf
   "graphql" :graphql})

;; --- Git shell helpers ---

(defn git-ls-tree
  "Shell out to `git ls-tree -r --long HEAD` for the given repo path.
   Returns raw output string. Throws on non-zero exit."
  [repo-path]
  (let [{:keys [exit out err]} (shell/sh "git" "-C" (str repo-path)
                                         "ls-tree" "-r" "--long" "HEAD")]
    (when (not= 0 exit)
      (throw (ex-info (str "git ls-tree failed: " (str/trim err))
                      {:exit exit :repo-path (str repo-path)})))
    out))

(defn git-line-counts
  "Shell out to `git grep -c '' HEAD` for the given repo path.
   Returns a map of {path line-count}. Binary files are omitted by git grep."
  [repo-path]
  (let [{:keys [exit out]} (shell/sh "git" "-C" (str repo-path)
                                     "grep" "-c" "" "HEAD")]
    ;; exit 0 = matches found, exit 1 = no matches (empty repo)
    (if (#{0 1} exit)
      (->> (str/split-lines out)
           (remove str/blank?)
           (reduce (fn [m line]
                     (let [;; format: HEAD:path:count — split from right
                           colon-idx (str/last-index-of line ":")
                           count-str (subs line (inc colon-idx))
                           prefix    (subs line 0 colon-idx)
                           ;; strip "HEAD:" prefix
                           path      (subs prefix (inc (str/index-of prefix ":")))]
                       (assoc m path (parse-long count-str))))
                   {}))
      (throw (ex-info (str "git grep failed with exit " exit)
                      {:exit exit :repo-path (str repo-path)})))))

;; --- Parsers ---

(defn parse-ls-tree
  "Parse output of `git ls-tree -r --long HEAD` into a seq of maps.
   Each map has :mode, :type, :sha, :size (long or nil for submodules), and :path."
  [text]
  (when-not (str/blank? text)
    (->> (str/split-lines text)
         (remove str/blank?)
         (mapv (fn [line]
                 ;; format: <mode> SP <type> SP <sha> SP+ <size> TAB <path>
                 (let [tab-idx  (str/index-of line "\t")
                       path     (subs line (inc tab-idx))
                       metadata (str/trim (subs line 0 tab-idx))
                       parts    (str/split metadata #"\s+")]
                   {:mode (nth parts 0)
                    :type (nth parts 1)
                    :sha  (nth parts 2)
                    :size (let [s (nth parts 3)]
                            (when (not= "-" s) (parse-long s)))
                    :path path}))))))

;; --- Directory extraction ---

(defn paths->dirs
  "Extract unique directory paths from a seq of file paths.
   Returns a sorted seq of directory path strings, including all intermediates.
   Root directory is represented as \".\"."
  [file-paths]
  (->> file-paths
       (mapcat (fn [path]
                 (let [parts (str/split path #"/")]
                   (when (> (count parts) 1)
                     (->> (range 1 (count parts))
                          (map #(str/join "/" (take % parts))))))))
       set
       (cons ".")
       sort
       vec))

;; --- Tx-data builders ---

(defn- file-ext
  "Extract file extension from a path, or nil if none."
  [path]
  (let [filename (last (str/split path #"/"))
        dot-idx  (str/last-index-of filename ".")]
    (when (and dot-idx (pos? dot-idx))
      (subs filename (inc dot-idx)))))

(defn- dir-of
  "Return the directory portion of a file path, or \".\" for root-level files."
  [path]
  (let [slash-idx (str/last-index-of path "/")]
    (if slash-idx
      (subs path 0 slash-idx)
      ".")))

(defn- parent-of
  "Return the parent directory of a directory path, or nil for root."
  [dir-path]
  (when (not= "." dir-path)
    (let [slash-idx (str/last-index-of dir-path "/")]
      (if slash-idx
        (subs dir-path 0 slash-idx)
        "."))))

(defn dir->tx-data
  "Build a Datomic entity map for a directory. Uses tempids for self and parent
   so references resolve within a single transaction."
  [dir-path]
  (let [parent (parent-of dir-path)]
    (cond-> {:db/id    (str "dir-" dir-path)
             :dir/path dir-path}
      parent (assoc :dir/parent (str "dir-" parent)))))

(defn file->tx-data
  "Build a Datomic entity map for a file.
   `file-map` is a parsed ls-tree entry, `line-counts` is {path count}."
  [file-map line-counts]
  (let [{:keys [path size]} file-map
        ext   (file-ext path)
        lang  (when ext (ext->lang ext))
        lines (get line-counts path)]
    (cond-> {:file/path      path
             :file/directory (str "dir-" (dir-of path))}
      ext   (assoc :file/ext ext)
      size  (assoc :file/size size)
      lang  (assoc :file/lang lang)
      lines (assoc :file/lines lines))))

;; --- Import orchestration ---

(defn- imported-file-paths
  "Return the set of file paths already fully imported (have :file/size)."
  [db]
  (->> (d/q '[:find ?path
              :where [?e :file/path ?path] [?e :file/size _]]
            db)
       (into #{} (map first))))

(defn- build-import-plan
  "Build deterministic import plan maps from parsed ls-tree and existing set."
  [all-files existing]
  (let [to-import  (remove #(existing (:path %)) all-files)
        file-paths (mapv :path all-files)
        dirs       (paths->dirs file-paths)]
    {:all-files all-files
     :to-import to-import
     :dirs dirs
     :skipped (- (count all-files) (count to-import))}))

(defn- plan->tx-data
  [{:keys [to-import dirs]} line-counts]
  (let [dir-tx  (mapv dir->tx-data dirs)
        file-tx (mapv #(file->tx-data % line-counts) to-import)]
    (into (into dir-tx file-tx)
          [{:db/id "datomic.tx" :tx/op :import :tx/source :deterministic}])))

(defn import-files!
  "Import file and directory structure from repo-path into Datomic via conn.
   Returns a summary map with :files-imported, :dirs-imported, :files-skipped, :elapsed-ms."
  [conn repo-path]
  (let [start-ms    (System/currentTimeMillis)
        raw         (git-ls-tree repo-path)
        all-files   (parse-ls-tree raw)
        line-counts (git-line-counts repo-path)
        existing    (imported-file-paths (d/db conn))
        {:keys [to-import dirs skipped] :as plan}
        (build-import-plan all-files existing)
        tx-data     (plan->tx-data plan line-counts)]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    (let [elapsed (- (System/currentTimeMillis) start-ms)]
      (binding [*out* *err*]
        (println (str "Imported " (count to-import) " files, "
                      (count dirs) " dirs, "
                      "skipped " skipped " already present. "
                      "(" elapsed " ms)")))
      {:files-imported (count to-import)
       :dirs-imported  (count dirs)
       :files-skipped  skipped
       :elapsed-ms     elapsed})))
