(ns noum.tui.core
  "TTY detection and output dispatch."
  (:require [clojure.string :as str]))

(defn interactive?
  "True when running in an interactive terminal (not piped, not CI)."
  []
  (and (some? (System/console))
       (nil? (System/getenv "CI"))
       (nil? (System/getenv "NOUM_NON_INTERACTIVE"))))

(defn utf8?
  "True when the locale suggests UTF-8 support."
  []
  (let [locale (str (System/getenv "LANG") (System/getenv "LC_ALL"))]
    (str/includes? (str/lower-case locale) "utf")))

(defn eprint
  "Print to stderr (human-facing output)."
  [& args]
  (binding [*out* *err*]
    (apply print args)
    (flush)))

(defn eprintln
  "Println to stderr."
  [& args]
  (binding [*out* *err*]
    (apply println args)))
