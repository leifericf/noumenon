(ns noum.electron
  "Auto-download packaged Noumenon Electron app from GitHub Releases."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.string :as str]
            [noum.paths :as paths]
            [noum.tui.core :as tui]
            [noum.tui.spinner :as spinner]))

(def ^:private github-repo "leifericf/noumenon")

(defn installed? []
  (fs/exists? paths/electron-app))

(defn- macos? []
  (str/starts-with? (System/getProperty "os.name" "") "Mac"))

(defn- asset-pattern []
  (if (macos?)
    #"Noumenon-.*\.dmg"
    #"Noumenon-.*\.AppImage"))

(defn- latest-release-info []
  (let [url  (str "https://api.github.com/repos/" github-repo "/releases/latest")
        resp (http/get url {:headers {"Accept" "application/vnd.github.v3+json"}})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "GitHub API returned " (:status resp)) {:status (:status resp)})))
    (let [body (json/parse-string (:body resp) true)]
      {:tag    (:tag_name body)
       :assets (mapv (fn [a] {:name (:name a) :url (:browser_download_url a)})
                     (:assets body))})))

(defn- find-electron-asset [assets]
  (first (filter #(re-matches (asset-pattern) (:name %)) assets)))

(defn- extract-dmg!
  "Mount DMG, copy .app to ui-dir, unmount."
  [dmg-path]
  (let [mount-point (str (fs/create-temp-dir {:prefix "noum-dmg-"}))]
    (try
      (proc/shell {:out :string :err :string}
                  "hdiutil" "attach" (str dmg-path)
                  "-nobrowse" "-mountpoint" mount-point)
      (let [app-src (first (filter #(str/ends-with? (str %) ".app")
                                   (fs/list-dir mount-point)))]
        (when-not app-src
          (throw (ex-info "No .app found in DMG" {:mount mount-point})))
        (fs/create-dirs paths/ui-dir)
        (when (fs/exists? paths/electron-app)
          (proc/shell {:out :string :err :string} "rm" "-rf" paths/electron-app))
        ;; Use cp -R to preserve symlinks in Frameworks/
        (proc/shell {:out :string :err :string}
                    "cp" "-R" (str app-src) paths/electron-app))
      (finally
        (proc/shell {:out :string :err :string :continue true}
                    "hdiutil" "detach" mount-point "-quiet")))))

(defn- extract-appimage!
  "Copy AppImage to ui-dir and make executable."
  [appimage-path]
  (fs/create-dirs paths/ui-dir)
  (when (fs/exists? paths/electron-app)
    (fs/delete paths/electron-app))
  (fs/copy appimage-path paths/electron-app)
  (fs/set-posix-file-permissions paths/electron-app "rwxr-xr-x"))

(defn download!
  "Download the latest packaged Electron app. Returns app path."
  []
  (let [s       (spinner/start "Checking latest Noumenon UI release...")
        release (latest-release-info)
        asset   (find-electron-asset (:assets release))]
    (when-not asset
      ((:stop s) "No Electron app found in release")
      (throw (ex-info (str "No Electron app asset found in release " (:tag release)
                           ". Expected " (if (macos?) "DMG" "AppImage") " file.")
                      {:tag (:tag release) :os (System/getProperty "os.name")})))
    ((:stop s) (str "Found " (:name asset) " in " (:tag release)))
    (let [s2       (spinner/start (str "Downloading " (:name asset) "..."))
          tmp-dir  (str (fs/create-temp-dir {:prefix "noum-electron-"}))
          tmp-file (str (fs/path tmp-dir (:name asset)))]
      (let [resp (http/get (:url asset) {:as :stream :follow-redirects true})]
        (with-open [in  (:body resp)
                    out (java.io.FileOutputStream. tmp-file)]
          (clojure.java.io/copy in out)))
      ((:stop s2) (str (:name asset) " downloaded."))
      (let [s3 (spinner/start "Installing...")]
        (if (macos?)
          (extract-dmg! tmp-file)
          (extract-appimage! tmp-file))
        (fs/delete-tree tmp-dir)
        ((:stop s3) (str "Installed to " paths/electron-app)))
      paths/electron-app)))

(defn ensure!
  "Ensure packaged Electron app is installed. Download if not. Returns app path."
  []
  (if (installed?)
    paths/electron-app
    (do (tui/eprintln "First run: downloading Noumenon UI...")
        (download!))))

(defn launch-cmd
  "Return the process command to launch the packaged Electron app."
  [app-path port]
  (if (macos?)
    ;; Launch the binary inside .app directly so env vars are inherited
    [(str (fs/path app-path "Contents" "MacOS" "Noumenon"))]
    [(str app-path)]))

(defn launch-env
  "Return env map for launching the Electron app."
  [port]
  (assoc (into {} (System/getenv)) "NOUMENON_PORT" (str port)))
