(ns noum.tui.style
  "ANSI styling primitives.
   Respects NO_COLOR (https://no-color.org) and non-TTY output.")

(defn- color?
  "True when ANSI colors should be emitted."
  []
  (and (nil? (System/getenv "NO_COLOR"))
       (some? (System/console))))

(def ^:private esc "\033[")

(defn- wrap [code s]
  (if (color?)
    (str esc code "m" s esc "0m")
    (str s)))

(defn bold [s] (wrap "1" s))
(defn dim [s] (wrap "2" s))
(defn italic [s] (wrap "3" s))
(defn red [s] (wrap "31" s))
(defn green [s] (wrap "32" s))
(defn yellow [s] (wrap "33" s))
(defn blue [s] (wrap "34" s))
(defn cyan [s] (wrap "36" s))
(defn gray [s] (wrap "90" s))

(defn clear-line [] (if (color?) (str esc "2K\r") ""))
(defn cursor-up [n] (if (color?) (str esc n "A") ""))
(defn hide-cursor [] (if (color?) (str esc "?25l") ""))
(defn show-cursor [] (if (color?) (str esc "?25h") ""))
