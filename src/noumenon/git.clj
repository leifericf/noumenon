(ns noumenon.git
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.util :refer [log! truncate]])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]
           [java.util Date]))

;; --- URL detection and cloning ---

(defn git-url?
  "True if s looks like a Git remote URL (https://, git@, or .git suffix)."
  [s]
  (boolean (or (re-matches #"https?://.+" s)
               (re-matches #"git@.+" s))))

(defn url->repo-name
  "Extract repository name from a Git URL.
   https://github.com/ring-clojure/ring.git -> ring"
  [url]
  (-> url
      (str/replace #"\.git$" "")
      (str/replace #"/$" "")
      (str/split #"[/:]")
      last))

(defn- extract-hostname
  "Extract hostname from a Git URL. Returns nil if not parseable."
  [url]
  (when-let [[_ host] (or (re-find #"https?://([^/:]+)" url)
                          (re-find #"git@([^:]+):" url))]
    host))

(def ^:private blocked-ip-patterns
  "Regex patterns matching private/loopback IP ranges (RFC-1918, RFC-5737, loopback, link-local)."
  [#"^127\." #"^10\." #"^172\.(1[6-9]|2[0-9]|3[01])\." #"^192\.168\."
   #"^0\." #"^169\.254\." #"^::1$" #"^fc00:" #"^fe80:" #"^fd"])

(defn- private-ip?
  "True if ip-str matches a private, loopback, or link-local range."
  [ip-str]
  (some #(re-find % ip-str) blocked-ip-patterns))

(defn- blocked-address?
  "True if addr is private, loopback, or link-local. Handles IPv4-mapped IPv6 addresses
   by re-canonicalizing to the underlying IPv4 address."
  [^java.net.InetAddress addr]
  (let [ip (.getHostAddress addr)]
    (or (private-ip? ip)
        (.isLoopbackAddress addr)
        (.isLinkLocalAddress addr)
        (.isSiteLocalAddress addr)
        ;; IPv4-mapped IPv6 (::ffff:x.x.x.x): re-canonicalize to check IPv4 form
        (when (instance? java.net.Inet6Address addr)
          (let [canon (java.net.InetAddress/getByAddress (.getAddress addr))]
            (or (.isLoopbackAddress canon)
                (.isLinkLocalAddress canon)
                (.isSiteLocalAddress canon)
                (private-ip? (.getHostAddress canon))))))))

(defn- validate-clone-url!
  "Validate that a Git URL does not resolve to a private/loopback address.
   Throws ex-info on blocked addresses."
  [url]
  (when-let [host (extract-hostname url)]
    (try
      (let [addrs (java.net.InetAddress/getAllByName host)]
        (doseq [^java.net.InetAddress addr addrs]
          (when (blocked-address? addr)
            (throw (ex-info "Blocked: URL resolves to private/loopback address"
                            {:url url :host host :ip (.getHostAddress addr)})))))
      (catch java.net.UnknownHostException _
        (throw (ex-info (str "Cannot resolve hostname: " host)
                        {:url url :host host}))))))

(defn clone!
  "Clone a Git URL into target-dir. Validates URL does not resolve to private IPs. Throws on failure."
  [url target-dir]
  (validate-clone-url! url)
  (io/make-parents (io/file target-dir "dummy"))
  (let [{:keys [exit err]} (shell/sh "git" "clone" url (str target-dir))]
    (when-not (zero? exit)
      (throw (ex-info (str "git clone failed: " (str/trim err))
                      {:exit exit :url url :target target-dir})))))

(defn clone-bare!
  "Clone a Git URL as a bare repository. No working tree.
   Validates URL does not resolve to private IPs. Throws on failure."
  [url target-dir]
  (validate-clone-url! url)
  (.mkdirs (io/file target-dir))
  (let [{:keys [exit err]} (shell/sh "git" "clone" "--bare" url (str target-dir))]
    (when-not (zero? exit)
      (throw (ex-info (str "git clone --bare failed: " (str/trim err))
                      {:exit exit :url url :target target-dir})))))

(defn bare-repo?
  "True if the path looks like a bare git repository."
  [path]
  (let [f (io/file path)]
    (and (.isDirectory f) (.exists (io/file f "HEAD")))))

(defn fetch!
  "Fetch latest from all remotes in a bare repo. Returns true on success."
  [repo-path]
  (let [{:keys [exit err]} (shell/sh "git" "--git-dir" (str repo-path) "fetch" "--all" "--prune")]
    (when-not (zero? exit)
      (throw (ex-info (str "git fetch failed: " (str/trim err))
                      {:exit exit :repo-path (str repo-path)})))
    true))

(defn git-dir-args
  "Return git CLI args for addressing a repo — handles both bare and working tree."
  [repo-path]
  (if (bare-repo? repo-path)
    ["--git-dir" (str repo-path)]
    ["-C" (str repo-path)]))

(defn head-sha
  "Return the current HEAD SHA for a git repository, or nil on failure."
  [repo-path]
  (let [args (into ["git"] (concat (git-dir-args repo-path) ["rev-parse" "HEAD"]))
        {:keys [exit out]} (apply shell/sh args)]
    (when (zero? exit) (str/trim out))))

;; Git log format: %x01 = record separator, %x00 = field separator.
;; %B (raw body) can contain arbitrary text including blank lines,
;; so we bracket it with %x00 to make parsing unambiguous.
;; --numstat appends per-file diff stats after the format output.
(def ^:private git-log-format
  "%x01%H%x00%P%x00%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI%x00%B%x00")

(def ^:private max-message-length 4096)

(def ^:private max-git-output-bytes
  "Maximum bytes accepted from git subprocess output (100 MB).
   Prevents unbounded memory consumption on very large repositories."
  100000000)

(defn- parse-iso-instant [s]
  (try
    (->> s
         (.parse DateTimeFormatter/ISO_OFFSET_DATE_TIME)
         Instant/from
         Date/from)
    (catch Exception e
      (log! (str "git/warn: Skipping commit with unparseable date: " s " — " (.getMessage e)))
      nil)))

(defn- resolve-rename-path
  "Resolve git rename syntax '{old => new}/rest' to the destination path.
   E.g. '{tests => flask/testsuite}/foo.py' -> 'flask/testsuite/foo.py'
         'src/{old.py => new.py}' -> 'src/new.py'"
  [path]
  (if-let [[_ prefix _ dest suffix] (re-matches #"(.*?)\{([^}]*?) => ([^}]*?)\}(.*)" path)]
    (-> (str prefix dest suffix)
        (str/replace #"//" "/")
        (str/replace #"^/" ""))
    path))

(defn- parse-numstat-line
  "Parse a --numstat line: 'additions\\tdeletions\\tfilename'.
   Binary files show '-' for both counts. Resolves rename syntax to dest path.
   Returns [path additions deletions] or nil."
  [line]
  (when-let [[_ adds dels path] (re-matches #"(\d+|-)\t(\d+|-)\t(.+)" (str/trim line))]
    [(resolve-rename-path path)
     (if (= adds "-") 0 (parse-long adds))
     (if (= dels "-") 0 (parse-long dels))]))

(defn- parse-numstat
  "Parse numstat text into {:changed-files [paths] :additions n :deletions n}.
   Deduplicates file paths (renames can produce duplicates)."
  [text]
  (let [parsed (->> (str/split-lines text)
                    (map str/trim)
                    (remove str/blank?)
                    (keep parse-numstat-line))]
    {:changed-files (vec (distinct (map first parsed)))
     :additions     (transduce (map second) + 0 parsed)
     :deletions     (transduce (map peek) + 0 parsed)}))

(defn- parse-record [text]
  (let [fields (str/split text #"\x00" -1)]
    (when (>= (count fields) 9)
      ;; Fields 0-7 are fixed headers, last field is numstat.
      ;; The body (%B) sits between index 8 and second-to-last.
      ;; If the body contains embedded \x00, extra fields appear
      ;; between 8 and the end — we rejoin them to reconstruct it.
      (let [[sha parents aname aemail adate cname cemail cdate] fields
            authored  (parse-iso-instant adate)
            committed (parse-iso-instant cdate)]
        (when (and authored committed)
          (let [numstat (parse-numstat (peek fields))
                body    (str/join "\u0000" (subvec fields 8 (dec (count fields))))]
            (merge {:sha             sha
                    :parent-shas     (if (str/blank? parents) [] (str/split parents #" "))
                    :author-name     aname
                    :author-email    aemail
                    :authored-at     authored
                    :committer-name  cname
                    :committer-email cemail
                    :committed-at    committed
                    :message         (str/trim body)}
                   numstat)))))))

;; --- Issue reference extraction ---

(def ^:private issue-ref-patterns
  "Patterns for extracting issue references from commit messages."
  [#"https?://[^\s\)\]>\"']+"          ;; URLs
   #"(?<![A-Za-z0-9/])[A-Z][A-Z0-9]+-\d+" ;; Jira-style: PROJ-123
   #"(?<=\s|^|\()#\d+"                 ;; GitHub-style: #123
   #"(?<=\s|^|\()GH-\d+"])             ;; GitHub alt: GH-42

(defn extract-issue-refs
  "Extract unique issue references (URLs, keys like #123, PROJ-456) from text.
   Returns a sorted set of strings, or nil if none found."
  [text]
  (when-not (str/blank? text)
    (let [refs (->> issue-ref-patterns
                    (mapcat #(re-seq % text))
                    (map str/trim)
                    (into (sorted-set)))]
      (when (seq refs) refs))))

;; --- Commit classification ---

(def ^:private conventional-prefix-re
  #"(?i)^(fix|feat|refactor|chore|docs|test|style|perf|revert|ci|build)[\(:].*")

(def ^:private kind-keywords
  {:fix      #"(?i)\b(fix|bug|patch|hotfix|resolve|repair)\b"
   :feat     #"(?i)\b(add|implement|feature|introduce|new)\b"
   :refactor #"(?i)\b(refactor|restructure|reorganize|simplify|clean\s?up)\b"
   :docs     #"(?i)\b(doc|documentation|readme|comment|javadoc)\b"
   :test     #"(?i)\b(test|spec|coverage|assert)\b"
   :style    #"(?i)\b(format|lint|whitespace|indent|style)\b"
   :perf     #"(?i)\b(perf|performance|optimize|speed|cache)\b"
   :chore    #"(?i)\b(chore|bump|upgrade|dependency|deps|version)\b"})

(defn classify-commit
  "Classify a commit message into a kind keyword.
   Tries conventional commit prefix first, then merge/revert detection,
   then keyword heuristics. Returns a keyword."
  [message]
  (let [msg (str/trim (or message ""))]
    (cond
      (str/blank? msg) :other
      (re-matches conventional-prefix-re msg)
      (-> msg (str/split #"[\(:]" 2) first str/lower-case str/trim
          ({"fix" :fix "feat" :feat "refactor" :refactor "chore" :chore
            "docs" :docs "test" :test "style" :style "perf" :perf
            "revert" :revert "ci" :chore "build" :chore}))
      (re-find #"(?i)^merge\b" msg)  :merge
      (re-find #"(?i)^revert\b" msg) :revert
      :else (or (->> kind-keywords
                     (some (fn [[kind re]] (when (re-find re msg) kind))))
                :other))))

(defn parse-commits
  "Parse raw git log output (produced by git-log) into a sequence of commit maps."
  [log-text]
  (if (str/blank? log-text)
    []
    (->> (str/split log-text #"\x01")
         (remove str/blank?)
         (into [] (keep parse-record)))))

(defn- person-tx-data
  "Build person entity tx-data. Deduplicates when author == committer."
  [author-tid author-email author-name committer-tid committer-email committer-name]
  (cond-> [{:db/id author-tid :person/email author-email :person/name author-name}]
    (not= author-email committer-email)
    (conj {:db/id committer-tid :person/email committer-email
           :person/name committer-name})))

(defn commit->tx-data
  "Convert a parsed commit map into a Datomic tx-data vector.
   Uses tempids for persons and files so Datomic can resolve references
   within a single transaction (upserts via :db.unique/identity).
   `repo-uri` is used to upsert the repo entity and link commits to it."
  [repo-uri {:keys [sha parent-shas author-name author-email authored-at
                    committer-name committer-email committed-at message
                    changed-files additions deletions]}]
  (let [author-tid    (str "person-" author-email)
        committer-tid (str "person-" committer-email)
        commit-tid    (str "commit-" sha)
        file-tids     (mapv #(str "file-" %) changed-files)
        commit        (cond-> {:db/id               commit-tid
                               :git/sha             sha
                               :git/type            :commit
                               :commit/message      (truncate message max-message-length)
                               :commit/kind         (classify-commit message)
                               :commit/author       author-tid
                               :commit/authored-at  authored-at
                               :commit/committer    committer-tid
                               :commit/committed-at committed-at}
                        (pos? (or additions 0)) (assoc :commit/additions additions)
                        (pos? (or deletions 0)) (assoc :commit/deletions deletions)
                        (seq parent-shas)        (assoc :commit/parents
                                                        (mapv #(vector :git/sha %) parent-shas))
                        (seq file-tids)          (assoc :commit/changed-files file-tids)
                        (extract-issue-refs message) (assoc :commit/issue-refs
                                                            (extract-issue-refs message)))]
    (-> (person-tx-data author-tid author-email author-name
                        committer-tid committer-email committer-name)
        (into [commit
               {:repo/uri repo-uri :repo/commits [commit-tid]}
               {:db/id "datomic.tx" :tx/op :import :tx/source :deterministic}])
        (into (map (fn [[path tid]] {:db/id tid :file/path path}))
              (map vector changed-files file-tids)))))

(defn git-log
  "Shell out to git log on the given repo path. Returns raw output string.
   Works with both bare and working-tree repos."
  [repo-path]
  (let [args (into ["git"] (concat (git-dir-args repo-path)
                                   ["log" "--topo-order" "--reverse"
                                    (str "--format=" git-log-format) "--numstat"]))
        {:keys [exit out err]} (apply shell/sh args)]
    (when (not= 0 exit)
      (throw (ex-info (str "git log failed: " (str/trim err))
                      {:exit exit :repo-path (str repo-path)})))
    (when (> (count out) max-git-output-bytes)
      (throw (ex-info (str "git log output exceeds " max-git-output-bytes
                           " bytes (" (count out) "). "
                           "Repository is too large for in-memory import.")
                      {:size (count out)
                       :limit max-git-output-bytes
                       :repo-path (str repo-path)})))
    out))

;; --- Import orchestration ---

(defn- imported-shas
  "Return the set of commit SHAs already in the database."
  [db]
  (->> (d/q '[:find ?sha :where [?e :git/sha ?sha] [?e :git/type :commit]] db)
       (into #{} (map first))))

(defn unimported-commits
  "Filter parsed commits to only those not yet in the database."
  [db commits]
  (let [existing (imported-shas db)]
    (into [] (remove #(existing (:sha %))) commits)))

(defn- build-import-plan
  "Build deterministic import plan for git commits."
  [db commits]
  (let [to-import (unimported-commits db commits)]
    {:all commits
     :to-import to-import
     :skipped (- (count commits) (count to-import))}))

(defn- transact-commits!
  [conn repo-uri commits progress-fn]
  (let [total (count commits)]
    (doseq [[i commit] (map-indexed vector commits)]
      (try
        (d/transact conn {:tx-data (commit->tx-data repo-uri commit)})
        (catch Exception e
          (throw (ex-info (str "Failed to import commit " (:sha commit)
                               " (" (inc i) "/" total "): " (.getMessage e))
                          {:sha (:sha commit) :index (inc i)
                           :changed-files (:changed-files commit)}
                          e))))
      (when (and (pos? total) (zero? (mod (inc i) 100)))
        (log! (str "  [" (inc i) "/" total "] commits imported...")))
      (when progress-fn
        (progress-fn {:current (inc i) :total total :message (str "commit " (subs (:sha commit) 0 7))})))))

(defn import-commits!
  "Import git history from repo-path into Datomic via conn.
   Parses git log, filters already-imported commits, transacts one per tx.
   `repo-uri` identifies the repository entity.
   Returns a summary map with :commits-imported, :commits-skipped, :elapsed-ms."
  ([conn repo-path repo-uri] (import-commits! conn repo-path repo-uri nil))
  ([conn repo-path repo-uri progress-fn]
   (let [start-ms  (System/currentTimeMillis)
         raw       (git-log repo-path)
         all       (parse-commits raw)
         {:keys [to-import skipped] :as _plan}
         (build-import-plan (d/db conn) all)]
     (when (and (empty? all) (seq raw))
       (log! "WARNING: git log produced output but no commits were parsed"))
     (when (empty? all)
       (log! "WARNING: no commits found in" (str repo-path)))
     (transact-commits! conn repo-uri to-import progress-fn)
     (let [elapsed (- (System/currentTimeMillis) start-ms)]
       (log! (str "Imported " (count to-import) " commits, "
                  "skipped " skipped " already present. "
                  "(" elapsed " ms)"))
       {:commits-imported (count to-import)
        :commits-skipped  skipped
        :elapsed-ms       elapsed}))))

;; --- Perforce via git-p4 ---

(defn p4-depot-path?
  "True if s looks like a Perforce depot path (starts with //)."
  [s]
  (boolean (and (string? s) (str/starts-with? s "//"))))

(defn p4-available?
  "True if git-p4 is available (git p4 subcommand works)."
  []
  (zero? (:exit (shell/sh "git" "p4" "--help"))))

(defn p4-clone?
  "True if repo-path is a git-p4 clone (has p4 remote refs)."
  [repo-path]
  (let [p4-refs (io/file repo-path ".git" "refs" "remotes" "p4")]
    (and (.isDirectory p4-refs)
         (pos? (count (.list p4-refs))))))

(defn p4-depot->clone-name
  "Derive a local clone directory name from a depot path.
   //depot/ProjectA/main/... -> ProjectA-main
   //stream/main/...         -> main
   //depot/...               -> depot"
  [depot-path]
  (let [segments (-> depot-path
                     (str/replace #"^//" "")
                     (str/replace #"/\.\.\.$" "")
                     (str/replace #"/$" "")
                     (str/split #"/"))
        ;; Use sub-path segments if available, else fall back to depot name
        name-parts (if (> (count segments) 1) (rest segments) segments)]
    (-> (str/join "-" name-parts)
        (str/replace #"[^a-zA-Z0-9\-_.]" ""))))

(defn p4-clone-path
  "Local clone path for a depot path: data/repos/<derived-name>."
  [depot-path]
  (str "data/repos/" (p4-depot->clone-name depot-path)))

(def ^:private default-p4-excludes
  "Default binary exclusion patterns, loaded from p4-excludes.edn."
  (delay
    (some-> (io/resource "p4-excludes.edn") slurp edn/read-string)))

(defn p4-exclude-patterns
  "Compute the final exclusion pattern list given options.
   Options:
     :no-default-excludes?  — skip default patterns (default false)
     :extra-excludes        — additional patterns to exclude (vector of strings)
     :includes              — patterns to remove from excludes (vector of strings)"
  [{:keys [no-default-excludes? extra-excludes includes]}]
  (let [defaults (when-not no-default-excludes?
                   (->> (vals @default-p4-excludes) (apply concat)))
        all      (concat defaults extra-excludes)
        include-set (set includes)]
    (->> all (remove include-set) distinct vec)))

(defn- p4-exclude-args
  "Build -/ arguments for git p4 clone from a list of exclusion patterns."
  [patterns]
  (mapcat (fn [p] ["-/" p]) patterns))

(defn validate-p4-depot-path!
  "Validate a Perforce depot path. Must start with //, contain only safe chars,
   and have at least one depot name segment. Throws on invalid input."
  [depot-path]
  (when-not (and (string? depot-path)
                 (re-matches #"//[a-zA-Z0-9_\-./]+" depot-path)
                 (not (str/includes? depot-path "..")))
    (throw (ex-info "Invalid Perforce depot path"
                    {:depot-path depot-path
                     :message "Depot path must start with // and contain only alphanumeric, dash, underscore, dot, or slash characters"})))
  (when-not (p4-available?)
    (throw (ex-info "git-p4 is not available. Install git-p4 to use Perforce support."
                    {:depot-path depot-path}))))

(defn p4-clone!
  "Clone a Perforce depot path via git-p4 into target-dir.
   Options:
     :excludes             — override exclusion patterns (vector of strings)
     :no-default-excludes? — skip default binary excludes
     :extra-excludes       — additional patterns to exclude
     :includes             — patterns to remove from default excludes
     :use-client-spec?     — use P4 workspace view for filtering
     :max-changes          — limit history depth (number of changelists)"
  [depot-path target-dir opts]
  (validate-p4-depot-path! depot-path)
  (let [excludes   (or (:excludes opts)
                       (p4-exclude-patterns opts))
        args       (cond-> ["git" "p4" "clone"]
                     (:use-client-spec? opts) (conj "--use-client-spec")
                     (not (:use-client-spec? opts)) (into (p4-exclude-args excludes))
                     (:max-changes opts) (conj (str "--max-changes=" (:max-changes opts)))
                     true (conj (str depot-path "@all"))
                     true (conj "--destination" (str target-dir)))
        _          (log! (str "git-p4: cloning " depot-path " into " target-dir
                              " (" (count excludes) " exclusion patterns)"))
        {:keys [exit err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git p4 clone failed: " (str/trim (or err "")))
                      {:exit exit :depot-path depot-path :target target-dir
                       :stderr (when err (subs err 0 (min (count err) 500)))})))
    (log! "git-p4: clone complete")
    target-dir))

(defn p4-sync!
  "Sync a git-p4 clone with latest Perforce changelists.
   Runs `git p4 sync` then `git p4 rebase`."
  [repo-path]
  (let [sync-args ["-C" (str repo-path) "p4" "sync"]
        {:keys [exit err]} (apply shell/sh "git" sync-args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git p4 sync failed: " (str/trim (or err "")))
                      {:exit exit :repo-path (str repo-path)}))))
  (let [rebase-args ["-C" (str repo-path) "p4" "rebase"]
        {:keys [exit err]} (apply shell/sh "git" rebase-args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git p4 rebase failed: " (str/trim (or err "")))
                      {:exit exit :repo-path (str repo-path)}))))
  (log! (str "git-p4: synced " repo-path))
  true)
