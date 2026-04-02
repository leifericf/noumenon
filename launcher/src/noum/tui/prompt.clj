(ns noum.tui.prompt
  "Free-text input component."
  (:require [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn ask
  "Prompt for free-text input. Returns trimmed string, or default when empty.
   Non-interactive: returns default."
  ([message] (ask message nil))
  ([message default]
   (if-not (tui/interactive?)
     default
     (let [hint (when default (str (style/dim (str "[" default "]")) " "))]
       (tui/eprint (str (style/bold "? ") message " " (or hint "")))
       (let [input (str/trim (or (read-line) ""))]
         (if (str/blank? input) default input))))))
