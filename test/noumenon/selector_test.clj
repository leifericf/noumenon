(ns noumenon.selector-test
  (:require [clojure.test :refer [deftest is]]
            [noumenon.selector :as selector]))

(def sample-files
  [{:file/path "src/noumenon/analyze.clj" :file/lang :clojure}
   {:file/path "src/noumenon/http.clj" :file/lang :clojure}
   {:file/path "test/noumenon/analyze_test.clj" :file/lang :clojure}
   {:file/path "README.md" :file/lang :markdown}])

(deftest include-and-exclude-filters
  (let [{:keys [files summary]}
        (selector/apply-filters sample-files
                                {:paths #{}
                                 :includes ["src/**/*.clj"]
                                 :excludes ["**/http.clj"]
                                 :langs #{}})]
    (is (= ["src/noumenon/analyze.clj"] (mapv :file/path files)))
    (is (= 3 (:excluded summary)))))

(deftest path-and-lang-filters
  (let [{:keys [files]}
        (selector/apply-filters sample-files
                                {:paths #{"src/noumenon"}
                                 :includes []
                                 :excludes []
                                 :langs #{:clojure}})]
    (is (= ["src/noumenon/analyze.clj" "src/noumenon/http.clj"]
           (mapv :file/path files)))))
