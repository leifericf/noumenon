(ns noum.jre
  "Auto-download and manage JRE from Adoptium."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noum.paths :as paths]
            [noum.tui.core :as tui]
            [noum.tui.spinner :as spinner])
  (:import [java.security MessageDigest]))

(def ^:private jre-version "21")

(defn- detect-os []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os "mac")   "mac"
      (str/includes? os "linux") "linux"
      (str/includes? os "win")   "windows"
      :else (throw (ex-info (str "Unsupported OS: " os) {})))))

(defn- detect-arch []
  (let [arch (System/getProperty "os.arch")]
    (case arch
      "aarch64" "aarch64"
      "arm64"   "aarch64"
      "amd64"   "x64"
      "x86_64"  "x64"
      (throw (ex-info (str "Unsupported architecture: " arch) {})))))

(defn- adoptium-url [os arch]
  (str "https://api.adoptium.net/v3/binary/latest/"
       jre-version "/ga/" os "/" arch
       "/jre/hotspot/normal/eclipse"))

(defn- adoptium-checksum-url [os arch]
  (str "https://api.adoptium.net/v3/checksum/latest/"
       jre-version "/ga/" os "/" arch
       "/jre/hotspot/normal/eclipse"))

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

(defn- verify-checksum!
  "Verify archive SHA256 against Adoptium checksum API. Throws on mismatch."
  [archive-path os arch]
  (let [s (spinner/start "Verifying JRE integrity...")]
    (try
      (let [resp     (http/get (adoptium-checksum-url os arch)
                               {:throw false :follow-redirects true})
            expected (when (= 200 (:status resp))
                       (first (re-seq #"[0-9a-f]{64}" (:body resp))))
            actual   (sha256-file archive-path)]
        (cond
          (nil? expected)
          (do ((:stop s) "Warning: could not fetch checksum from Adoptium API")
              (tui/eprintln "  Continuing without checksum verification."))

          (= expected actual)
          ((:stop s) "SHA256 verified")

          :else
          (do ((:stop s) "FAILED")
              (throw (ex-info "SHA256 mismatch -- JRE download may be corrupted. Try again."
                              {:expected expected :actual actual})))))
      (catch Exception e
        (if (= "SHA256 mismatch -- JRE download may be corrupted. Try again." (.getMessage e))
          (throw e)
          (do ((:stop s) "Warning: checksum verification failed")
              (tui/eprintln (str "  " (.getMessage e) ". Continuing without verification."))))))))

(defn installed?
  "Check if a JRE is available at the expected location."
  []
  (let [java-bin (str (fs/path paths/jre-dir "bin" "java"))]
    (fs/exists? java-bin)))

(defn java-home
  "Return the JRE home directory."
  []
  (when (installed?) paths/jre-dir))

(defn- find-jre-root
  "After extracting, find the actual JRE root (may be nested in a directory).
   Filters out non-directory entries (e.g. the archive file)."
  [extract-dir]
  (let [dirs (filterv fs/directory? (fs/list-dir extract-dir))]
    (if (= 1 (count dirs))
      ;; macOS: jdk-21.../Contents/Home or jdk-21.../
      (let [inner (str (first dirs))
            home  (str (fs/path inner "Contents" "Home"))]
        (if (fs/exists? home) home inner))
      (str extract-dir))))

(defn download!
  "Download and install JRE. Returns the JRE directory path."
  []
  (let [os       (detect-os)
        arch     (detect-arch)
        url      (adoptium-url os arch)
        s        (spinner/start (str "Downloading JRE " jre-version " for " os "/" arch "..."))
        s2-atom  (atom nil)
        tmp-dir  (str (fs/create-temp-dir {:prefix "noum-jre-"}))
        ext      (if (= os "windows") ".zip" ".tar.gz")
        archive  (str (fs/path tmp-dir (str "jre" ext)))]
    (try
      (let [resp (http/get url {:as :stream :follow-redirects true})]
        (with-open [in (:body resp)
                    out (io/output-stream archive)]
          (io/copy in out)))
      ((:stop s) "JRE downloaded.")
      (verify-checksum! archive os arch)
      (let [s2 (spinner/start "Extracting JRE...")]
        (reset! s2-atom s2)
        (fs/create-dirs paths/jre-dir)
        (if (= ext ".zip")
          (fs/unzip archive tmp-dir)
          (proc/shell {:dir tmp-dir} "tar" "xzf" archive))
        ;; Move extracted contents to paths/jre-dir
        (let [root (find-jre-root tmp-dir)]
          (doseq [f (fs/list-dir root)]
            (let [target (str (fs/path paths/jre-dir (fs/file-name f)))]
              (when-not (str/ends-with? (str f) ext)
                (fs/move f target {:replace-existing true})))))
        ((:stop s2) "JRE installed."))
      paths/jre-dir
      (catch Exception e
        (if-let [s2 @s2-atom]
          ((:stop s2) "JRE extraction failed.")
          ((:stop s) "JRE download failed."))
        (throw e))
      (finally
        (fs/delete-tree tmp-dir)))))

(defn ensure!
  "Ensure JRE is installed. Download if not. Returns JRE directory."
  []
  (if (installed?)
    paths/jre-dir
    (do (tui/eprintln "First run: downloading JRE (~200MB) to ~/.noumenon/")
        (download!))))
