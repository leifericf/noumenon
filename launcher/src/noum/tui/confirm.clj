(ns noum.tui.confirm
  "y/n confirmation prompt."
  (:require [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn ask
  "Prompt for y/n confirmation. Returns true/false.
   Non-interactive: returns default-val."
  ([message] (ask message true))
  ([message default-val]
   (if-not (tui/interactive?)
     default-val
     (let [hint (if default-val "[Y/n]" "[y/N]")]
       (tui/eprint (str (style/bold "? ") message " " (style/dim hint) " "))
       (let [input (str/trim (or (read-line) ""))]
         (cond
           (= "" input) default-val
           (#{"y" "yes"} (str/lower-case input)) true
           (#{"n" "no"} (str/lower-case input)) false
           :else default-val))))))
