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

(defn ask-secret
  "Prompt for a secret (token/key). Masks the display.
   Returns trimmed string, or nil when empty/skipped.
   Non-interactive: returns nil."
  [message]
  (when (tui/interactive?)
    (tui/eprint (str (style/bold "? ") message " " (style/dim "(paste, then Enter — blank to skip) ")))
    (let [input (str/trim (or (read-line) ""))]
      (when-not (str/blank? input)
        ;; Overwrite the line to mask the token
        (tui/eprint (str "\r" (style/clear-line)
                         (style/green "✓ ") message " "
                         (style/dim (str (subs input 0 (min 4 (count input))) "****")) "\n"))
        input))))
