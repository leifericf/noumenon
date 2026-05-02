(ns noum.tui.confirm
  "y/n confirmation prompt."
  (:require [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn ask
  "Prompt for y/n confirmation; re-prompt on garbage. Empty input → default-val.
   Non-interactive: returns default-val."
  ([message] (ask message false))
  ([message default-val]
   (if-not (tui/interactive?)
     (do (tui/eprintln (str message " → " (if default-val "yes" "no") " (non-interactive)"))
         default-val)
     (let [hint (if default-val "[Y/n]" "[y/N]")]
       (tui/eprint (str (style/bold "? ") message " " (style/dim hint) " "))
       (loop []
         (let [input (str/trim (or (read-line) ""))]
           (cond
             (= "" input)                          default-val
             (#{"y" "yes"} (str/lower-case input)) true
             (#{"n" "no"}  (str/lower-case input)) false
             :else
             (do (tui/eprint (str (style/dim "Please answer y/n. ")
                                  (style/bold "? ") message " " (style/dim hint) " "))
                 (recur)))))))))
