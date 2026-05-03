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

(declare max-repo-path-len)

(defn validate-repo-path
  "Validate that repo-path exists, is a directory, and contains .git.
   Returns an error reason string, or nil if valid.

   Type and length are checked before any filesystem call: a non-nil
   non-string would throw IllegalArgumentException inside `io/file`
   (no `as-file` impl for vectors/numbers/maps/keywords/booleans), and
   an unbounded string lets callers walk a multi-MB path through every
   downstream `.exists`/`.isDirectory`. Both surface here as clean
   400-shaped reasons via the caller's `when-let` + throw. nil is
   unchanged: callers already do their own missing-field check before
   reaching this validator."
  [repo-path]
  (cond
    (nil? repo-path)
    nil
    (not (string? repo-path))
    "must be a string"
    (> (count repo-path) max-repo-path-len)
    (str "exceeds maximum length of " max-repo-path-len " characters")
    :else
    (let [f (io/file repo-path)]
      (cond
        (not (.exists f))                    "does not exist"
        (not (.isDirectory f))               "not a directory"
        (not (.exists (io/file f ".git")))   "not a git repository"))))

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

(defn validate-repo-path-input!
  "Type-, length-, and blank-check the repo identifier shared by every
   endpoint that funnels through `with-repo`. nil passes silently so
   the caller's missing-field check stays the source of truth for
   'required'. Non-strings, oversized strings, and blank strings throw
   ex-info with `:status 400`. The full FS-shape validator
   (`validate-repo-path`) only fits handlers that strictly require a
   filesystem path; this helper is for the polymorphic identifier
   (filesystem path, git URL, or db-name) that `with-repo` accepts."
  [repo-path]
  (when (some? repo-path)
    (cond
      (not (string? repo-path))
      (let [msg "repo_path must be a string"]
        (throw (ex-info msg
                        {:status 400 :message msg :user-message msg
                         :field "repo_path" :type (some-> repo-path class .getName)})))
      (str/blank? repo-path)
      (let [msg "repo_path must not be blank"]
        (throw (ex-info msg
                        {:status 400 :message msg :user-message msg
                         :field "repo_path"})))
      (> (count repo-path) max-repo-path-len)
      (let [msg (str "repo_path exceeds maximum length of " max-repo-path-len " characters")]
        (throw (ex-info msg
                        {:status 400 :message msg :user-message msg
                         :field "repo_path" :length (count repo-path) :max max-repo-path-len}))))))

(defn validate-string-length!
  "Throw ex-info with :status 400 if `s` is supplied but isn't a string,
   or is a string longer than `max-len`. nil passes silently so optional
   fields stay optional. The earlier guard `(when (and (string? s) ...))`
   silently let non-strings through, which meant a JSON request like
   `{\"branch\": [\"a\"]}` flowed past every validator (HTTP and MCP) and
   crashed downstream as a 500 — every caller relied on this function
   as a type-AND-length boundary even though it only enforced length."
  [field-name s max-len]
  (cond
    (nil? s) nil
    (not (string? s))
    (let [msg (str field-name " must be a string")]
      (throw (ex-info msg
                      {:status 400 :message msg :user-message msg
                       :field field-name :type (some-> s class .getName)})))
    (> (count s) max-len)
    (let [msg (str field-name " exceeds maximum length of " max-len " characters")]
      (throw (ex-info msg
                      {:status 400 :message msg :user-message msg
                       :field field-name :length (count s) :max max-len})))))

;; --- Shared input limits ---
;; Used by both HTTP handlers (src/noumenon/http.clj) and the MCP layer
;; (src/noumenon/mcp.clj) so the two interfaces stay in lockstep on what
;; counts as a valid request shape. Adjust here once; both surfaces follow.

(def max-repo-path-len   "Filesystem paths"             4096)
(def max-param-value-len "Datalog query param values"   4096)
(def max-question-len    "Free-form ask questions"      8000)
(def max-query-name-len  "Named query identifiers"      256)
(def max-branch-name-len
  "Git/Perforce branch names. Capped well below the natural human-name
   limit so the synthesized delta db-name (`<repo>__<safe-branch>-<hash6>__<basis7>`)
   stays under the POSIX path-component limit (255 bytes) for a
   reasonable repo basename. Math: branch + 18 fixed (`__-<hash6>__<basis7>`)
   + repo ≤ 255 → branch ≤ 237 - repo. 200 leaves ~37 chars of headroom
   for the repo basename, which covers virtually every real-world case;
   `delta-db-name` does a final check too for the long-repo edge case."
  200)
(def max-host-len        "host:port hostnames"          256)
(def max-db-name-len     "Datomic db-name identifiers"  256)
(def max-run-id-len      "Benchmark/introspect run IDs" 256)
(def max-params-count    "Max named-query :params keys" 20)
(def max-param-key-len   "Named-query :params key len"  256)

(def valid-introspect-targets
  "The set of targets the introspect loop knows how to optimize. Lives at
   the cross-layer level so MCP, HTTP, and CLI surfaces all reject the
   same typo'd inputs instead of silently dropping them."
  #{:examples :system-prompt :rules :code :train})

(defn validate-introspect-targets!
  "Throw ex-info if `target-str` (a comma-separated `--target` value) names
   any target not in `valid-introspect-targets`. Pass-through on nil or
   blank — those mean 'no filter' and are not user errors. Trims whitespace
   around each comma so `examples, code` validates the same as `examples,code`.
   The thrown ex-info carries `:user-message` so the caller can render it
   directly without leaking the exception class."
  [target-str]
  (when (and target-str (not (str/blank? target-str)))
    (let [parts (->> (str/split target-str #",")
                     (map str/trim)
                     (remove str/blank?))
          unknown (seq (remove (comp valid-introspect-targets keyword) parts))]
      (when unknown
        (let [msg (str "Unknown introspect targets: " (str/join ", " unknown)
                       ". Valid: " (str/join ", "
                                             (map name valid-introspect-targets)))]
          (throw (ex-info msg
                          {:status 400 :message msg :user-message msg
                           :field "target" :unknown (vec unknown)})))))))

(defn validate-params!
  "Validate a named-query :params map: cap on entry count, key length,
   and value length. Throws ex-info :status 400 on any breach. Accepts
   nil/missing or an empty map."
  [params]
  (when params
    (when (> (count params) max-params-count)
      (let [msg (str "params: max " max-params-count " entries")]
        (throw (ex-info msg {:status 400 :message msg :user-message msg}))))
    (doseq [[k v] params]
      (validate-string-length! (str "params key " k) (name (keyword k)) max-param-key-len)
      (validate-string-length! (str "params." (name (keyword k))) (str v) max-param-value-len))))

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
