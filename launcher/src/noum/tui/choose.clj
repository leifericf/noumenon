(ns noum.tui.choose
  "Menu selection component."
  (:require [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn- with-raw-mode
  "Execute f with terminal in raw mode. Restores original settings on exit."
  [f]
  (let [saved (-> (Runtime/getRuntime)
                  (.exec (into-array String ["/bin/sh" "-c" "stty -g < /dev/tty"]))
                  (doto .waitFor)
                  .getInputStream
                  slurp
                  str/trim)]
    (try
      (-> (Runtime/getRuntime)
          (.exec (into-array String ["/bin/sh" "-c" "stty raw -echo < /dev/tty"]))
          .waitFor)
      (f)
      (finally
        (-> (Runtime/getRuntime)
            (.exec (into-array String ["/bin/sh" "-c" (str "stty " saved " < /dev/tty")]))
            .waitFor)))))

(defn select
  "Display a selection menu. Options is a vec of strings or maps with :label.
   Returns the selected option (string or map). Non-interactive: returns first option."
  [message options]
  (if-not (tui/interactive?)
    (do (tui/eprintln (str message " → " (if (map? (first options))
                                           (:label (first options))
                                           (first options))))
        (first options))
    (let [labels (mapv #(if (map? %) (:label %) %) options)
          n      (count options)]
      (tui/eprintln (style/bold (str "? " message)))
      (tui/eprint (style/hide-cursor))
      (try
        (with-raw-mode
          (fn []
            (loop [selected 0]
              (doseq [i (range n)]
                (tui/eprintln (str (if (= i selected)
                                     (str (style/cyan "▸ ") (style/bold (nth labels i)))
                                     (str "  " (nth labels i))))))
              (let [ch (.read System/in)]
                ;; Erase the menu lines for redraw
                (tui/eprint (str (style/cursor-up n)
                                 (str/join (repeat n (str (style/clear-line) "\n")))
                                 (style/cursor-up n)))
                (cond
                  ;; Enter / carriage return
                  (#{10 13} ch) (do (tui/eprintln (str (style/green "✓ ") message " → "
                                                       (style/cyan (nth labels selected))))
                                    (nth options selected))
                  ;; Escape sequence (arrows)
                  (= 27 ch)    (let [_ (.read System/in) ;; [
                                     arrow (.read System/in)]
                                 (recur (case (int arrow)
                                          65 (mod (dec selected) n) ;; A = up
                                          66 (mod (inc selected) n) ;; B = down
                                          selected)))
                  ;; k/j vim keys
                  (= 107 ch)   (recur (mod (dec selected) n))
                  (= 106 ch)   (recur (mod (inc selected) n))
                  ;; q/Ctrl-C
                  (#{113 3} ch) nil
                  :else         (recur selected))))))
        (finally
          (tui/eprint (style/show-cursor)))))))
