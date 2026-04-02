(ns noum.tui.spinner
  "Animated spinner for long-running operations."
  (:require [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(def ^:private unicode-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])
(def ^:private ascii-frames ["|" "/" "-" "\\"])

(defn- frames []
  (if (tui/utf8?) unicode-frames ascii-frames))

(defn start
  "Start a spinner with a message. Returns a map with :stop and :fail fns."
  [message]
  (if-not (tui/interactive?)
    (do (tui/eprintln message)
        {:stop (fn ([] nil) ([_msg] nil))
         :fail (fn ([] (tui/eprintln (str "FAILED: " message)))
                 ([msg] (tui/eprintln (str "FAILED: " msg))))})
    (let [running (atom true)
          fs      (frames)
          t (Thread.
             (fn []
               (tui/eprint (style/hide-cursor))
               (loop [i 0]
                 (when @running
                   (tui/eprint (str (style/clear-line)
                                    (style/cyan (nth fs (mod i (count fs))))
                                    " " message))
                   (Thread/sleep 80)
                   (recur (inc i))))
               (tui/eprint (str (style/clear-line) (style/show-cursor)))))
          stop! (fn [icon msg]
                  (reset! running false)
                  (Thread/sleep 100)
                  (tui/eprintln (str icon " " msg)))
          check (if (tui/utf8?) "✓" "*")
          cross (if (tui/utf8?) "✗" "x")]
      (.setDaemon t true)
      (.start t)
      {:stop (fn
               ([] (stop! (style/green check) message))
               ([msg] (stop! (style/green check) msg)))
       :fail (fn
               ([] (stop! (style/red cross) message))
               ([msg] (stop! (style/red cross) msg)))})))
