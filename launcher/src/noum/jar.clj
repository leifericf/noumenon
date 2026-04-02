(ns noum.jar
  "Auto-download noumenon.jar from GitHub Releases."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [noum.paths :as paths]
            [noum.tui.core :as tui]
            [noum.tui.spinner :as spinner])
  (:import [java.security MessageDigest]))

(def ^:private github-repo "leifericf/noumenon")

(defn installed? []
  (fs/exists? paths/jar-path))

(defn path []
  (when (installed?) paths/jar-path))

(defn- latest-release-info []
  (let [url  (str "https://api.github.com/repos/" github-repo "/releases/latest")
        resp (http/get url {:headers {"Accept" "application/vnd.github.v3+json"}})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "GitHub API returned " (:status resp)
                           ". No releases found for " github-repo ".")
                      {:status (:status resp)})))
    (let [body (json/parse-string (:body resp) true)]
      {:tag    (:tag_name body)
       :assets (mapv (fn [a] {:name (:name a) :url (:browser_download_url a)})
                     (:assets body))})))

(defn- find-jar-asset [assets]
  (first (filter #(re-matches #"noumenon-.*\.jar" (:name %)) assets)))

(defn- find-sha-asset [assets jar-name]
  (first (filter #(= (:name %) (str jar-name ".sha256")) assets)))

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
  "Verify JAR SHA256 against sidecar. Throws on mismatch. Warns if no sidecar."
  [jar-path assets jar-name]
  (if-let [sha-asset (find-sha-asset assets jar-name)]
    (let [s        (spinner/start "Verifying integrity...")
          sha-resp (http/get (:url sha-asset) {:follow-redirects true})
          expected (first (re-seq #"[0-9a-f]{64}" (:body sha-resp)))
          actual   (sha256-file jar-path)]
      (if (= expected actual)
        ((:stop s) "SHA256 verified")
        (do ((:stop s) "FAILED")
            (fs/delete jar-path)
            (throw (ex-info "SHA256 mismatch -- download may be corrupted. Try again."
                            {:expected expected :actual actual})))))
    (tui/eprintln "  Warning: no .sha256 sidecar in release, skipping checksum verification.")))

(defn- current-version
  "Read the installed JAR's version from the daemon, or nil."
  []
  (try
    (let [resp (http/get "http://127.0.0.1:7891/health"
                         {:timeout 2000 :throw false})]
      (when (= 200 (:status resp))
        (-> (json/parse-string (:body resp) true) :data :version)))
    (catch Exception _ nil)))

(defn download!
  "Download the latest noumenon.jar. Returns the jar path, or nil if already up to date."
  []
  (let [s       (spinner/start "Checking latest Noumenon release...")
        release (latest-release-info)
        asset   (find-jar-asset (:assets release))]
    (when-not asset
      ((:stop s) "No JAR found in release")
      (throw (ex-info (str "No JAR asset found in release " (:tag release)) {})))
    (let [remote-ver (:tag release)
          local-ver  (current-version)]
      (if (and local-ver (= (str "v" local-ver) remote-ver))
        (do ((:stop s) (str "Already at latest version (" remote-ver ").")) nil)
        (do ((:stop s) (str "Found " remote-ver (when local-ver (str " (current: v" local-ver ")"))))
            (let [s2 (spinner/start (str "Downloading " (:name asset) "..."))]
              (fs/create-dirs paths/lib-dir)
              (let [resp (http/get (:url asset) {:as :stream :follow-redirects true})]
                (with-open [in (:body resp)
                            out (io/output-stream paths/jar-path)]
                  (io/copy in out)))
              ((:stop s2) (str (:name asset) " downloaded.")))
            (verify-checksum! paths/jar-path (:assets release) (:name asset))
            paths/jar-path)))))

(defn ensure!
  "Ensure noumenon.jar is installed. Download if not. Returns jar path."
  []
  (if (installed?)
    paths/jar-path
    (do (tui/eprintln "First run: downloading noumenon.jar (~50MB) to ~/.noumenon/")
        (download!))))
