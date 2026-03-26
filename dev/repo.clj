(ns repo
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(def ring-repo-url "https://github.com/ring-clojure/ring.git")
(def ring-repo-dir "data/repos/ring")

(defn clone-ring
  "Clone the Ring repository into data/repos/ring if not already present."
  []
  (let [dir (io/file ring-repo-dir)]
    (if (.exists dir)
      (println "Ring repo already exists at" ring-repo-dir)
      (do
        (println "Cloning Ring repo into" ring-repo-dir "...")
        (io/make-parents (io/file ring-repo-dir "dummy"))
        (let [{:keys [exit err]} (sh "git" "clone" ring-repo-url ring-repo-dir)]
          (if (zero? exit)
            (println "Done.")
            (throw (ex-info "Failed to clone Ring repo" {:exit exit :err err}))))))))
