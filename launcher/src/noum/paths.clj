(ns noum.paths
  "Shared path constants for ~/.noumenon/."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn ensure-private!
  "Set file permissions to owner-only (600) if the file exists.
   No-op on platforms without POSIX file permissions (e.g. Windows)."
  [path]
  (when (fs/exists? path)
    (try
      (fs/set-posix-file-permissions path "rw-------")
      (catch UnsupportedOperationException _))))

(def noum-dir  (str (fs/path (fs/home) ".noumenon")))
(def jre-dir   (str (fs/path noum-dir "jre")))
(def lib-dir   (str (fs/path noum-dir "lib")))
(def jar-path  (str (fs/path lib-dir "noumenon.jar")))
(def config-path (str (fs/path noum-dir "config.edn")))
(def daemon-file (str (fs/path noum-dir "daemon.edn")))
(def daemon-log  (str (fs/path noum-dir "daemon.log")))
(def data-dir    (str (fs/path noum-dir "data")))
(def demo-manifest (str (fs/path data-dir "noumenon" "manifest.edn")))
(def ui-dir      (str (fs/path noum-dir "ui")))
(def electron-app
  (if (str/starts-with? (System/getProperty "os.name" "") "Mac")
    (str (fs/path ui-dir "Noumenon.app"))
    (str (fs/path ui-dir "Noumenon.AppImage"))))
