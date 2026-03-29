(ns noum.tui.style
  "ANSI styling primitives.")

(def ^:private esc "\033[")

(defn bold [s] (str esc "1m" s esc "0m"))
(defn dim [s] (str esc "2m" s esc "0m"))
(defn italic [s] (str esc "3m" s esc "0m"))
(defn red [s] (str esc "31m" s esc "0m"))
(defn green [s] (str esc "32m" s esc "0m"))
(defn yellow [s] (str esc "33m" s esc "0m"))
(defn blue [s] (str esc "34m" s esc "0m"))
(defn cyan [s] (str esc "36m" s esc "0m"))
(defn gray [s] (str esc "90m" s esc "0m"))

(defn clear-line [] (str esc "2K\r"))
(defn cursor-up [n] (str esc n "A"))
(defn hide-cursor [] (str esc "?25l"))
(defn show-cursor [] (str esc "?25h"))
