(ns noum.tui.spinner
  "Animated spinner for long-running operations."
  (:require [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(def ^:private frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn start
  "Start a spinner with a message. Returns a map with :stop fn."
  [message]
  (if-not (tui/interactive?)
    (do (tui/eprintln message) {:stop (fn [& _])})
    (let [running (atom true)
          t (Thread.
             (fn []
               (tui/eprint (style/hide-cursor))
               (loop [i 0]
                 (when @running
                   (tui/eprint (str (style/clear-line)
                                    (style/cyan (nth frames (mod i (count frames))))
                                    " " message))
                   (Thread/sleep 80)
                   (recur (inc i))))
               (tui/eprint (str (style/clear-line) (style/show-cursor)))))]
      (.setDaemon t true)
      (.start t)
      {:stop (fn
               ([] (reset! running false) (Thread/sleep 100)
                   (tui/eprintln (str (style/green "✓") " " message)))
               ([msg] (reset! running false) (Thread/sleep 100)
                      (tui/eprintln (str (style/green "✓") " " msg))))})))
