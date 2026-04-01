(ns noum.demo
  "Download and install a pre-built demo database from GitHub Releases."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [noum.paths :as paths]
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

(defn- latest-release-assets []
  (let [url  (str "https://api.github.com/repos/" github-repo "/releases/latest")
        resp (http/get url {:headers {"Accept" "application/vnd.github.v3+json"}})]
    (when (= 200 (:status resp))
      (let [body (json/parse-string (:body resp) true)]
        {:tag    (:tag_name body)
         :assets (mapv (fn [a] {:name (:name a) :url (:browser_download_url a)})
                       (:assets body))}))))

(defn- find-asset [assets pattern]
  (first (filter #(re-matches pattern (:name %)) assets)))

;; --- Download + verify ---

(defn- download-to-file! [url dest]
  (let [resp (http/get url {:as :stream :follow-redirects true})]
    (with-open [in (:body resp)
                out (io/output-stream (str dest))]
      (io/copy in out))))

(defn- download-and-verify!
  "Download the demo tarball and verify SHA256. Returns path to verified tarball."
  []
  (let [s       (spinner/start "Checking latest release for demo database...")
        release (latest-release-assets)]
    (when-not release
      ((:stop s) "Failed")
      (throw (ex-info "Cannot reach GitHub. Check your internet connection." {})))
    (let [tarball-asset (find-asset (:assets release) asset-pattern)
          sha-asset     (find-asset (:assets release) sha-pattern)]
      (when-not tarball-asset
        ((:stop s) "Not found")
        (throw (ex-info (str "No demo database found in release " (:tag release)
                             ". The maintainer may not have uploaded it yet.") {})))
      ((:stop s) (str "Found demo database in " (:tag release)))
      (let [tmp-dir  (str (fs/create-temp-dir {:prefix "noum-demo-"}))
            tar-path (str (fs/path tmp-dir (:name tarball-asset)))
            s2       (spinner/start (str "Downloading " (:name tarball-asset) "..."))]
        (download-to-file! (:url tarball-asset) tar-path)
        ((:stop s2) (str "Downloaded (" (-> (fs/size tar-path) (/ 1024) int) " KB)"))
        ;; Verify SHA256 if sidecar available
        (when sha-asset
          (let [sha-path (str (fs/path tmp-dir (:name sha-asset)))
                s3       (spinner/start "Verifying integrity...")]
            (download-to-file! (:url sha-asset) sha-path)
            (let [expected (first (re-seq #"[0-9a-f]{64}" (slurp sha-path)))
                  actual   (sha256-file tar-path)]
              (if (= expected actual)
                ((:stop s3) "SHA256 verified")
                (do ((:stop s3) "FAILED")
                    (fs/delete-tree tmp-dir)
                    (throw (ex-info "SHA256 mismatch — download may be corrupted. Try again."
                                    {:expected expected :actual actual})))))))
        {:tarball tar-path :tmp-dir tmp-dir}))))

;; --- Extraction ---

(defn- demo-db-exists? []
  (fs/directory? (fs/path paths/data-dir "noumenon" "noumenon")))

(defn- extract!
  "Extract the demo tarball into ~/.noumenon/data/."
  [tarball-path]
  (let [s (spinner/start "Extracting demo database...")]
    (fs/create-dirs paths/data-dir)
    ;; Extract to a temp staging dir first
    (let [stage (str (fs/create-temp-dir {:prefix "noum-demo-stage-"}))]
      (proc/shell {:dir stage} "tar" "xzf" tarball-path)
      ;; The tarball root is noumenon-demo/noumenon/ (Datomic system dir)
      ;; Target is ~/.noumenon/data/noumenon/
      (let [src-system (str (fs/path stage "noumenon-demo" "noumenon"))
            dst-system (str (fs/path paths/data-dir "noumenon"))]
        (when-not (fs/directory? src-system)
          ((:stop s) "Failed")
          (fs/delete-tree stage)
          (throw (ex-info "Tarball has unexpected structure (missing noumenon-demo/noumenon/)" {})))
        ;; Copy database subdirectories
        (doseq [sub ["noumenon" "noumenon-internal"]]
          (let [src (str (fs/path src-system sub))
                dst (str (fs/path dst-system sub))]
            (when (fs/directory? src)
              (when (fs/exists? dst) (fs/delete-tree dst))
              (fs/copy-tree src dst))))
        ;; Copy system catalog if target doesn't have one
        (doseq [f ["db.log" "log.idx"]]
          (let [src (str (fs/path src-system f))
                dst (str (fs/path dst-system f))]
            (when (and (fs/exists? src) (not (fs/exists? dst)))
              (fs/copy src dst))))
        ;; Copy manifest
        (let [src (str (fs/path src-system "manifest.edn"))
              dst (str (fs/path dst-system "manifest.edn"))]
          (when (fs/exists? src)
            (fs/copy src dst {:replace-existing true})))
        (fs/delete-tree stage))
      ((:stop s) "Demo database installed"))))

;; --- Public API ---

(defn demo-installed? []
  (fs/exists? paths/demo-manifest))

(defn install!
  "Download and install the demo database. Returns true on success."
  [{:keys [force]}]
  (when (and (demo-db-exists?) (not force))
    (tui/eprint "A 'noumenon' database already exists. Overwrite? [y/N] ")
    (flush)
    (let [answer (str (read-line))]
      (when-not (#{"y" "Y" "yes"} answer)
        (tui/eprintln "Cancelled.")
        (throw (ex-info "Cancelled by user" {:cancelled true})))))
  (let [{:keys [tarball tmp-dir]} (download-and-verify!)]
    (try
      (extract! tarball)
      true
      (finally
        (when tmp-dir (fs/delete-tree tmp-dir))))))
