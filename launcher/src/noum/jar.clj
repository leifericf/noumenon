(ns noum.jar
  "Auto-download noumenon.jar from GitHub Releases."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [noum.paths :as paths]
            [noum.tui.core :as tui]
            [noum.tui.spinner :as spinner]))

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

(defn download!
  "Download the latest noumenon.jar. Returns the jar path."
  []
  (let [s       (spinner/start "Checking latest Noumenon release...")
        release (latest-release-info)
        asset   (find-jar-asset (:assets release))]
    (when-not asset
      ((:stop s) "No JAR found in release")
      (throw (ex-info (str "No JAR asset found in release " (:tag release)) {})))
    ((:stop s) (str "Found " (:tag release)))
    (let [s2 (spinner/start (str "Downloading " (:name asset) "..."))]
      (fs/create-dirs paths/lib-dir)
      (let [resp (http/get (:url asset) {:as :stream :follow-redirects true})]
        (with-open [in (:body resp)
                    out (clojure.java.io/output-stream paths/jar-path)]
          (clojure.java.io/copy in out)))
      ((:stop s2) (str (:name asset) " installed.")))
    paths/jar-path))

(defn ensure!
  "Ensure noumenon.jar is installed. Download if not. Returns jar path."
  []
  (if (installed?)
    paths/jar-path
    (do (tui/eprintln "First run: downloading noumenon.jar (~50MB) to ~/.noumenon/")
        (download!))))
