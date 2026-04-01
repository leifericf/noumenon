#!/usr/bin/env bb
;; Build, test, and optionally release the demo database tarball.
;;
;; Usage:
;;   ./bin/build-demo-tarball.sh                    # build + test
;;   ./bin/build-demo-tarball.sh --release           # build + test + upload to GitHub release
;;   ./bin/build-demo-tarball.sh --tag v0.4.0        # build for a specific version/tag
;;   ./bin/build-demo-tarball.sh --provider claude-api --model sonnet
;;   ./bin/build-demo-tarball.sh --skip-build        # test existing tarball only
;;
;; Version resolution (in order):
;;   1. --tag flag (explicit)
;;   2. Git tag pointing at HEAD (if any)
;;   3. resources/version.edn (fallback)
;;
;; Prerequisites:
;;   - noumenon.jar + JRE installed (run `noum upgrade` first)
;;   - LLM credentials (NOUMENON_ZAI_TOKEN or ANTHROPIC_API_KEY)
;;   - `gh` CLI for --release (https://cli.github.com)

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

;; --- Config ---

(def repo-path (str (fs/canonicalize (fs/path (fs/cwd) (if (str/ends-with? (str (fs/cwd)) "bin") ".." ".")))))
(def java-path
  (or (when-let [j (fs/which "java")] (str j))
      (str (fs/path (fs/home) ".noumenon" "jre" "bin" "java"))))
(def local-jar (first (fs/glob (str (fs/path repo-path "target")) "noumenon-*.jar")))
(def installed-jar (str (fs/path (fs/home) ".noumenon" "lib" "noumenon.jar")))

;; --- Arg parsing ---

(defn parse-args [args]
  (loop [args args opts {:provider "glm" :model "sonnet"}]
    (if (empty? args)
      opts
      (let [[a & rest] args]
        (case a
          "--release"    (recur rest (assoc opts :release true))
          "--skip-build" (recur rest (assoc opts :skip-build true))
          "--provider"   (recur (next rest) (assoc opts :provider (first rest)))
          "--model"      (recur (next rest) (assoc opts :model (first rest)))
          "--tag"        (recur (next rest) (assoc opts :tag (first rest)))
          (recur rest opts))))))

;; --- Version resolution ---

(defn- git-tag-at-head
  "Return the version tag (v*) pointing at HEAD, or nil."
  []
  (let [r (proc/shell {:out :string :err :string :continue true}
                      "git" "-C" repo-path "describe" "--tags" "--exact-match" "HEAD")]
    (when (zero? (:exit r))
      (let [tag (str/trim (:out r))]
        (when (str/starts-with? tag "v") tag)))))

(defn- edn-version []
  (:version (edn/read-string (slurp (str (fs/path repo-path "resources" "version.edn"))))))

(defn resolve-version
  "Resolve version: --tag flag > git tag at HEAD > version.edn."
  [{:keys [tag]}]
  (or (when tag (str/replace tag #"^v" ""))
      (when-let [t (git-tag-at-head)] (str/replace t #"^v" ""))
      (edn-version)))

;; --- Helpers ---

(defn sh! [& args]
  (let [r (apply proc/shell {:out :string :err :string :continue true} args)]
    (when (pos? (:exit r))
      (println (:err r))
      (throw (ex-info (str "Command failed: " (str/join " " args)) {:exit (:exit r)})))
    (:out r)))

(defn sha256 [path]
  (str/trim
   (first
    (str/split
     (if (fs/which "sha256sum")
       (sh! "sha256sum" (str path))
       (sh! "shasum" "-a" "256" (str path)))
     #"\s+"))))

(defn step [label]
  (println (str "\n=== " label " ===")))

;; --- Build ---

(def demo-build-dir
  "Persistent build directory — kept for dev use after packaging."
  (str (fs/path (fs/home) ".noumenon" "demo-build")))

(defn- resolve-jar
  "Find the best JAR: local build (target/) > installed (~/.noumenon/lib/)."
  []
  (cond
    (and local-jar (fs/exists? (str local-jar)))  (str local-jar)
    (fs/exists? installed-jar)                     installed-jar
    :else nil))

(defn build! [{:keys [provider model version]}]
  (step (str "Building demo database v" version))
  (let [jar (resolve-jar)]
    (when-not jar
      (println "Error: No noumenon JAR found.")
      (println "  Build it: clj -T:build uber")
      (println "  Or install: noum upgrade")
      (System/exit 1)))

  (let [tarball  (str "noumenon-demo-v" version ".tar.gz")
        sha-file (str tarball ".sha256")
        db-dir   demo-build-dir]
    ;; Reuse existing build if present (digest is idempotent — only re-processes new/changed files)
    (if (fs/exists? (str (fs/path db-dir "noumenon" "noumenon")))
      (println (str "  Reusing existing build at " db-dir " (digest will update incrementally)"))
      (do (println (str "  Fresh build at " db-dir))
          (fs/create-dirs db-dir)))

    (println (str "  Version:  " version))
    (println (str "  Repo:     " repo-path))
    (println (str "  Provider: " provider " / " model))
    (println (str "  Build db: " db-dir " (kept for dev use)"))
    (println "")

    ;; Run pipeline (import + enrich + analyze + synthesize, skip benchmark)
    (let [jar (resolve-jar)]
      (println (str "  Using: " jar))
      (proc/shell {:dir repo-path :inherit true}
                  java-path "-jar" jar "digest" repo-path
                  "--db-dir" db-dir
                  "--provider" provider "--model" model
                  "--skip-benchmark"))

    ;; Write manifest
    (step "Writing manifest")
    (let [commit (str/trim (sh! "git" "-C" repo-path "rev-parse" "HEAD"))
          manifest (pr-str {:version      version
                            :created-at   (str (java.time.Instant/now))
                            :source-repo  "leifericf/noumenon"
                            :source-commit commit
                            :databases    ["noumenon" "noumenon-internal"]})]
      (spit (str (fs/path db-dir "noumenon" "manifest.edn")) manifest)
      (println (str "  Commit: " (subs commit 0 12))))

    ;; Package (from the persistent dir, don't delete it)
    (step "Packaging tarball")
    (let [stage-dir (str (fs/create-temp-dir {:prefix "noum-demo-stage-"}))
          stage     (str (fs/path stage-dir "noumenon-demo" "noumenon"))]
      ;; Copy (not move) so the build dir stays intact for dev use
      (fs/create-dirs (str (fs/path stage-dir "noumenon-demo")))
      (fs/copy-tree (str (fs/path db-dir "noumenon")) stage)
      ;; Clean lock files from the copy only
      (doseq [f (fs/glob stage "**/.lock")]
        (fs/delete f))
      (proc/shell {:dir stage-dir} "tar" "-czf" (str (fs/path repo-path tarball)) "noumenon-demo/")
      (fs/delete-tree stage-dir))

    ;; SHA256
    (let [hash (sha256 (str (fs/path repo-path tarball)))]
      (spit (str (fs/path repo-path sha-file)) (str hash "\n"))
      (println (str "  " tarball " (" (-> (fs/size (str (fs/path repo-path tarball))) (/ 1024) int) " KB)"))
      (println (str "  SHA256: " hash)))

    (println (str "\n  Build database kept at: " db-dir))
    (println "  Use with: noum start --db-dir ~/.noumenon/demo-build")))

;; --- Test ---

(defn test! [version]
  (let [tarball  (str "noumenon-demo-v" version ".tar.gz")
        sha-file (str tarball ".sha256")]
    (step "Testing tarball")
    (when-not (fs/exists? tarball)
      (println (str "Error: " tarball " not found"))
      (System/exit 1))

    ;; Verify SHA256
    (let [expected (str/trim (slurp sha-file))
          actual   (sha256 tarball)]
      (if (= expected actual)
        (println "  SHA256: verified")
        (do (println (str "  SHA256 MISMATCH: expected " expected " got " actual))
            (System/exit 1))))

    ;; Extract to temp and check structure
    (let [tmp (str (fs/create-temp-dir {:prefix "noum-demo-test-"}))]
      (proc/shell {:dir tmp} "tar" "xzf" (str (fs/path repo-path tarball)))
      (let [system-dir (str (fs/path tmp "noumenon-demo" "noumenon"))
            checks [[(str (fs/path system-dir "noumenon"))          "repo database"]
                    [(str (fs/path system-dir "noumenon-internal")) "metadata database"]
                    [(str (fs/path system-dir "manifest.edn"))      "manifest"]]]
        (doseq [[path label] checks]
          (if (fs/exists? path)
            (println (str "  " label ": present"))
            (do (println (str "  " label ": MISSING"))
                (System/exit 1)))))

      ;; Read and print manifest
      (let [manifest (edn/read-string (slurp (str (fs/path tmp "noumenon-demo" "noumenon" "manifest.edn"))))]
        (println (str "  Version: " (:version manifest)))
        (println (str "  Commit:  " (subs (:source-commit manifest) 0 12)))
        (println (str "  Created: " (:created-at manifest))))

      (fs/delete-tree tmp))
    (println "  All checks passed")))

;; --- Release ---

(defn release! [version]
  (let [tarball  (str "noumenon-demo-v" version ".tar.gz")
        sha-file (str tarball ".sha256")
        tag      (str "v" version)]
    (step (str "Uploading to GitHub release " tag))
    (when-not (fs/which "gh")
      (println "Error: gh CLI not found. Install: https://cli.github.com")
      (System/exit 1))

    ;; Check release exists
    (let [r (proc/shell {:out :string :err :string :continue true} "gh" "release" "view" tag)]
      (when (pos? (:exit r))
        (println (str "Error: Release " tag " not found. Create it first or push a tag."))
        (System/exit 1)))

    ;; Upload (overwrites existing assets with same name)
    (proc/shell {:inherit true} "gh" "release" "upload" tag tarball sha-file "--clobber")
    (println (str "  Uploaded to " tag))))

;; --- Main ---

(let [opts    (parse-args *command-line-args*)
      version (resolve-version opts)
      tarball (str "noumenon-demo-v" version ".tar.gz")]
  (println (str "Demo database version: " version
                (cond
                  (:tag opts)         " (from --tag)"
                  (git-tag-at-head)   " (from git tag at HEAD)"
                  :else               " (from version.edn)")))
  (when-not (:skip-build opts)
    (build! (assoc opts :version version)))
  (test! version)
  (when (:release opts)
    (release! version))
  (println (str "\n" (if (:release opts) "Released!" "Done.") " " tarball)))
