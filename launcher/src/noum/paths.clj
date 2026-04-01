(ns noum.paths
  "Shared path constants for ~/.noumenon/."
  (:require [babashka.fs :as fs]))

(defn ensure-private!
  "Set file permissions to owner-only (600) if the file exists."
  [path]
  (when (fs/exists? path)
    (fs/set-posix-file-permissions path "rw-------")))

(def noum-dir  (str (fs/path (fs/home) ".noumenon")))
(def jre-dir   (str (fs/path noum-dir "jre")))
(def lib-dir   (str (fs/path noum-dir "lib")))
(def jar-path  (str (fs/path lib-dir "noumenon.jar")))
(def config-path (str (fs/path noum-dir "config.edn")))
(def daemon-file (str (fs/path noum-dir "daemon.edn")))
(def daemon-log  (str (fs/path noum-dir "daemon.log")))
(def data-dir    (str (fs/path noum-dir "data")))
(def demo-manifest (str (fs/path data-dir "noumenon" "manifest.edn")))
