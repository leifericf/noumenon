(ns noumenon.selector
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- ->items [v]
  (cond
    (nil? v) []
    (string? v) (str/split v #",")
    (sequential? v) (mapcat ->items v)
    :else [(str v)]))

(defn- clean-items [v]
  (->> (->items v) (map str/trim) (remove str/blank?) vec))

(defn- repo-relative-path [repo-path path]
  (let [repo (-> repo-path io/file .getCanonicalPath)
        raw  (str/replace (str path) #"\\" "/")
        abs  (.getCanonicalPath (io/file raw))]
    (if (str/starts-with? abs repo)
      (-> abs
          (subs (min (count abs) (inc (count repo))))
          (str/replace #"^/+" ""))
      raw)))

(defn- normalize-path [repo-path path]
  (let [raw (-> path str (str/replace #"\\" "/") (str/replace #"^\./" ""))]
    (if (.exists (io/file raw))
      (repo-relative-path repo-path raw)
      raw)))

(defn normalize
  [repo-path {:keys [path include exclude lang]}]
  {:paths    (->> (clean-items path) (map #(normalize-path repo-path %)) set)
   :includes (clean-items include)
   :excludes (clean-items exclude)
   :langs    (->> (clean-items lang) (map keyword) set)})

(defn- path-match? [selected file-path]
  (or (= selected file-path)
      (str/starts-with? file-path (str (str/replace selected #"/+$" "") "/"))))

(defn- glob-match? [pattern file-path]
  (let [m (.getPathMatcher (java.nio.file.FileSystems/getDefault) (str "glob:" pattern))]
    (.matches m (java.nio.file.Paths/get file-path (make-array String 0)))))

(defn- matches-any? [pred coll]
  (or (empty? coll) (boolean (some pred coll))))

(defn keep-file?
  [{:keys [paths includes excludes langs]} {:file/keys [path lang]}]
  (let [path-ok (and (matches-any? #(path-match? % path) paths)
                     (matches-any? #(glob-match? % path) includes))
        lang-ok (or (empty? langs) (langs lang))
        ex-ok   (not-any? #(glob-match? % path) excludes)]
    (and path-ok lang-ok ex-ok)))

(defn apply-filters
  [files selector]
  (let [selected (filterv #(keep-file? selector %) files)]
    {:files selected
     :summary {:candidates (count files)
               :selected (count selected)
               :excluded (- (count files) (count selected))}}))
