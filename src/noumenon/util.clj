(ns noumenon.util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn log!
  "Print to stderr. Drop-in replacement for (binding [*out* *err*] (println ...))."
  [& args]
  (binding [*out* *err*] (apply println args)))

(defn truncate
  "Clamp string s to at most n characters. Returns nil for nil input."
  [s n]
  (when s
    (subs s 0 (min (count s) n))))

(defn escape-double-mustache
  "Escape double-mustache `{{` in untrusted content to prevent template injection.
   Only handles `{{` — verify any new template syntax is also escaped at injection points."
  [s]
  (str/replace (or s "") "{{" "{ {"))

(defn sha256-hex
  "Compute SHA-256 hex digest of a string."
  ^String [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(def default-db-dir
  "Default database storage directory, relative to cwd."
  "data/datomic")

(defn resolve-db-dir
  "Resolve the database storage directory from an options map.
   Uses :db-dir if present, otherwise defaults to data/datomic/ relative to cwd."
  [opts]
  (str (.getAbsolutePath (io/file (or (:db-dir opts) default-db-dir)))))

(defn validate-repo-path
  "Validate that repo-path exists, is a directory, and contains .git.
   Returns an error reason string, or nil if valid."
  [repo-path]
  (let [f (io/file repo-path)]
    (cond
      (not (.exists f))                    "does not exist"
      (not (.isDirectory f))               "not a directory"
      (not (.exists (io/file f ".git")))   "not a git repository")))

(defn derive-db-name
  "Extract database name from a repo path: canonicalize, take basename, sanitize.
   Only alphanumeric, hyphen, underscore, and dot are kept. Rejects '..' and empty results."
  [repo-path]
  (let [canonical (.getCanonicalPath (java.io.File. (str repo-path)))
        raw       (-> canonical (str/replace #"/+$" "") (str/split #"/") last)
        sanitized (str/replace raw #"[^a-zA-Z0-9\-_.]" "")]
    (if (and (seq sanitized) (not (re-matches #"\.+" sanitized)))
      sanitized
      (throw (ex-info "Cannot derive database name from repo path"
                      {:repo-path repo-path :basename raw :sanitized sanitized})))))

(defn validate-string-length!
  "Throw ex-info with :status 400 if s exceeds max-len characters.
   The :status + :message keys let HTTP handlers surface a clean 400
   response instead of falling through to a generic 500."
  [field-name s max-len]
  (when (and (string? s) (> (count s) max-len))
    (let [msg (str field-name " exceeds maximum length of " max-len " characters")]
      (throw (ex-info msg
                      {:status 400 :message msg
                       :field field-name :length (count s) :max max-len})))))

(defn env
  "Read an env var, with optional _FILE variant for Docker secrets.
   Tries VAR_FILE first (reads file contents, trimmed), then VAR."
  [var-name]
  (or (when-let [path (System/getenv (str var-name "_FILE"))]
        (let [f (io/file path)]
          (when (.isFile f)
            (str/trim (slurp f)))))
      (System/getenv var-name)))

(defn env-bool
  "Read an env var as a boolean. Returns true for \"true\"/\"1\"/\"yes\"."
  [var-name]
  (some-> (env var-name) str/lower-case #{"true" "1" "yes"} some?))

(defn env-int
  "Read an env var as an integer, or nil if absent/unparseable."
  [var-name]
  (when-let [s (env var-name)]
    (try (Integer/parseInt (str/trim s)) (catch NumberFormatException _ nil))))

(defn read-version
  "Read project version from version.edn on classpath."
  []
  (:version (edn/read-string (slurp (io/resource "version.edn")))))
