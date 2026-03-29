(ns noum.paths
  "Shared path constants for ~/.noumenon/."
  (:require [babashka.fs :as fs]))

(def noum-dir  (str (fs/path (fs/home) ".noumenon")))
(def jre-dir   (str (fs/path noum-dir "jre")))
(def lib-dir   (str (fs/path noum-dir "lib")))
(def jar-path  (str (fs/path lib-dir "noumenon.jar")))
(def config-path (str (fs/path noum-dir "config.edn")))
(def daemon-file (str (fs/path noum-dir "daemon.edn")))
(def daemon-log  (str (fs/path noum-dir "daemon.log")))
