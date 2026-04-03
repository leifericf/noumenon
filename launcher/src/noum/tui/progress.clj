(ns noum.tui.progress
  "Progress bar with percentage."
  (:require [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn bar
  "Render a progress bar. Returns a map with :update and :done fns.
   Non-interactive: prints milestone percentages."
  [message total]
  (if-not (tui/interactive?)
    (let [last-pct (atom -1)]
      {:update (fn [current]
                 (let [pct (if (pos? total) (int (* 100 (/ current total))) 0)]
                   (when (and (zero? (mod pct 25)) (not= pct @last-pct))
                     (reset! last-pct pct)
                     (tui/eprintln (str message " " pct "%")))))
       :done   (fn [] (tui/eprintln (str message " done.")))})
    (let [width  30
          filled-ch (if (tui/utf8?) "█" "#")
          empty-ch  (if (tui/utf8?) "░" ".")
          spin-ch   (if (tui/utf8?) "⠋" "*")]
      {:update (fn [current]
                 (let [pct   (if (pos? total) (int (* 100 (/ current total))) 0)
                       filled (int (* width (/ (min current total) (max total 1))))
                       empty  (- width filled)]
                   (tui/eprint (str (style/clear-line)
                                    (style/cyan spin-ch) " " message " "
                                    (style/green (apply str (repeat filled filled-ch)))
                                    (style/gray (apply str (repeat empty empty-ch)))
                                    " " pct "% (" current "/" total ")"))))
       :done   (fn []
                 (tui/eprint (style/clear-line))
                 (tui/eprintln (str (style/green "✓") " " message " done.")))})))
