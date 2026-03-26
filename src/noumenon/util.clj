(ns noumenon.util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn log!
  "Print to stderr. Drop-in replacement for (binding [*out* *err*] (println ...))."
  [& args]
  (binding [*out* *err*] (apply println args)))

(defn truncate
  "Clamp string s to at most n characters. Returns nil for nil input."
  [s n]
  (when s
    (subs s 0 (min (count s) n))))

(defn escape-template-vars
  "Escape template metacharacters in untrusted content to prevent injection."
  [s]
  (str/replace (or s "") "{{" "{ {"))

(defn sha256-hex
  "Compute SHA-256 hex digest of a string."
  ^String [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn read-version
  "Read project version from version.edn on classpath."
  []
  (:version (edn/read-string (slurp (io/resource "version.edn")))))
