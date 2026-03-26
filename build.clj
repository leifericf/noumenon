(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]))

(def version (:version (edn/read-string (slurp "resources/version.edn"))))
(def class-dir "target/classes")
(def uber-file (format "target/noumenon-%s.jar" version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis @basis :src-dirs ["src"] :class-dir class-dir})
  (b/uber {:class-dir class-dir :uber-file uber-file :basis @basis
           :main 'noumenon.main}))
