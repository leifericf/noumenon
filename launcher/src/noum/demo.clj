(ns noum.demo
  "Download and install a pre-built demo database from GitHub Releases."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [noum.paths :as paths]
            [noum.tui.confirm :as confirm]
            [noum.tui.core :as tui]
            [noum.tui.spinner :as spinner])
  (:import [java.security MessageDigest]))

(def ^:private github-repo "leifericf/noumenon")
(def ^:private asset-pattern #"noumenon-demo-.*\.tar\.gz$")
(def ^:private sha-pattern   #"noumenon-demo-.*\.tar\.gz\.sha256$")

;; --- SHA256 verification (cross-platform, no shell) ---

(defn- sha256-file [path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buf    (byte-array 8192)]
    (with-open [in (io/input-stream (str path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update digest buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

;; --- GitHub release asset discovery ---

(defn- parse-release [body]
  {:tag    (:tag_name body)
   :assets (mapv (fn [a] {:name (:name a) :url (:browser_download_url a)})
                 (:assets body))})

(defn- recent-releases
  "Fetch up to n recent releases (default 5)."
  ([] (recent-releases 5))
  ([n]
   (let [url  (str "https://api.github.com/repos/" github-repo "/releases?per_page=" n)
         resp (http/get url {:headers {"Accept" "application/vnd.github.v3+json"}})]
     (when (= 200 (:status resp))
       (mapv parse-release (json/parse-string (:body resp) true))))))

(defn- find-asset [assets pattern]
  (first (filter #(re-matches pattern (:name %)) assets)))

;; --- Download + verify ---

(defn- download-to-file! [url dest]
  (let [resp (http/get url {:as :stream :follow-redirects true})]
    (with-open [in (:body resp)
                out (io/output-stream (str dest))]
      (io/copy in out))))

(defn- verify-sha256!
  "Download SHA sidecar and verify tarball integrity. Throws on mismatch."
  [sha-asset tmp-dir tar-path]
  (let [sha-path (str (fs/path tmp-dir (:name sha-asset)))
        s        (spinner/start "Verifying integrity...")]
    (download-to-file! (:url sha-asset) sha-path)
    (let [expected (first (re-seq #"[0-9a-f]{64}" (slurp sha-path)))
          actual   (sha256-file tar-path)]
      (if (= expected actual)
        ((:stop s) "SHA256 verified")
        (do ((:fail s))
            (fs/delete-tree tmp-dir)
            (throw (ex-info "SHA256 mismatch — download may be corrupted. Try again."
                            {:expected expected :actual actual})))))))

(defn- resolve-release-assets!
  "Find a release containing a demo tarball. Checks the latest release first,
   then walks back through recent releases as a fallback."
  []
  (let [s        (spinner/start "Checking releases for demo database...")
        releases (recent-releases)]
    (when-not (seq releases)
      ((:fail s))
      (throw (ex-info "Cannot reach GitHub. Check your internet connection." {})))
    (if-let [{:keys [tag] :as hit}
             (first (filter #(find-asset (:assets %) asset-pattern) releases))]
      (do ((:stop s) (str "Found demo database in " tag))
          {:tarball-asset (find-asset (:assets hit) asset-pattern)
           :sha-asset     (find-asset (:assets hit) sha-pattern)})
      (do ((:fail s) "Not found")
          (throw (ex-info (str "No demo database found in the last "
                               (count releases) " releases.")
                          {}))))))

(defn- download-and-verify!
  "Download the demo tarball and verify SHA256. Returns path to verified tarball."
  []
  (let [{:keys [tarball-asset sha-asset]} (resolve-release-assets!)
        tmp-dir  (str (fs/create-temp-dir {:prefix "noum-demo-"}))
        tar-path (str (fs/path tmp-dir (:name tarball-asset)))
        s        (spinner/start (str "Downloading " (:name tarball-asset) "..."))]
    (download-to-file! (:url tarball-asset) tar-path)
    ((:stop s) (str "Downloaded (" (-> (fs/size tar-path) (/ 1024) int) " KB)"))
    (when sha-asset
      (verify-sha256! sha-asset tmp-dir tar-path))
    {:tarball tar-path :tmp-dir tmp-dir}))

;; --- Extraction ---

(defn- demo-db-exists? []
  (fs/directory? (fs/path paths/data-dir "noumenon" "noumenon")))

(defn- copy-db-dirs!
  "Copy database subdirectories, system catalog files, and manifest from src to dst."
  [src-system dst-system]
  (doseq [sub ["noumenon" "noumenon-internal"]]
    (let [src (str (fs/path src-system sub))
          dst (str (fs/path dst-system sub))]
      (when (fs/directory? src)
        (when (fs/exists? dst) (fs/delete-tree dst))
        (fs/copy-tree src dst))))
  (doseq [f ["db.log" "log.idx"]]
    (let [src (str (fs/path src-system f))
          dst (str (fs/path dst-system f))]
      (when (and (fs/exists? src) (not (fs/exists? dst)))
        (fs/copy src dst))))
  (let [src (str (fs/path src-system "manifest.edn"))
        dst (str (fs/path dst-system "manifest.edn"))]
    (when (fs/exists? src)
      (fs/copy src dst {:replace-existing true}))))

(defn- extract!
  "Extract the demo tarball into ~/.noumenon/data/."
  [tarball-path]
  (let [s     (spinner/start "Extracting demo database...")
        _     (fs/create-dirs paths/data-dir)
        stage (str (fs/create-temp-dir {:prefix "noum-demo-stage-"}))]
    (proc/shell {:dir stage} "tar" "xzf" tarball-path)
    (let [src-system (str (fs/path stage "noumenon-demo" "noumenon"))
          dst-system (str (fs/path paths/data-dir "noumenon"))]
      (when-not (fs/directory? src-system)
        ((:fail s))
        (fs/delete-tree stage)
        (throw (ex-info "Tarball has unexpected structure (missing noumenon-demo/noumenon/)" {})))
      (copy-db-dirs! src-system dst-system)
      (fs/delete-tree stage))
    ((:stop s) "Demo database installed")))

;; --- Public API ---

(defn demo-installed? []
  (fs/exists? paths/demo-manifest))

(defn install!
  "Download and install the demo database. Returns true on success."
  [{:keys [force]}]
  (when (and (demo-db-exists?) (not force))
    (when-not (confirm/ask "A 'noumenon' database already exists. Overwrite?" false)
      (tui/eprintln "Cancelled.")
      (throw (ex-info "Cancelled by user" {:cancelled true}))))
  (let [{:keys [tarball tmp-dir]} (download-and-verify!)]
    (try
      (extract! tarball)
      true
      (finally
        (when tmp-dir (fs/delete-tree tmp-dir))))))
