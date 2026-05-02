(ns noum.tui.choose
  "Menu selection component."
  (:require [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(def ^:private ^:const key-enter    10)
(def ^:private ^:const key-cr       13)
(def ^:private ^:const key-escape   27)
(def ^:private ^:const key-ctrl-c    3)
(def ^:private ^:const key-j       106)
(def ^:private ^:const key-k       107)
(def ^:private ^:const key-q       113)
(def ^:private ^:const arrow-up     65)
(def ^:private ^:const arrow-down   66)

(def ^:private ^:const esc-followup-grace-ms
  "Wait this long after a bare ESC byte for follow-up bytes (a CSI
   sequence arrives as `ESC [ <code>` — three bytes total). Local
   terminals deliver in <5ms; SSH/WSL stays under 50ms. 20ms is the
   sweet spot — long enough to avoid false positives, short enough that
   bare ESC feels instant."
  20)

(defn read-arrow!
  "Peek for an arrow-key sequence after a bare ESC has already been
   consumed. Returns `:up` / `:down` for arrows, nil for a bare ESC
   (no follow-up bytes within the grace period). Public so the unit
   test can exercise the bare-ESC behavior without wiring up a real
   terminal."
  [^java.io.InputStream tty]
  (Thread/sleep esc-followup-grace-ms)
  (when (pos? (.available tty))
    (.read tty)                ; consume the `[`
    (let [arrow (.read tty)]
      (cond
        (= arrow arrow-up)   :up
        (= arrow arrow-down) :down
        :else                nil))))

(defn- with-raw-mode
  "Execute f with terminal in raw mode, reading from /dev/tty.
   Passes the tty InputStream to f for direct key reads."
  [f]
  (let [saved (-> (Runtime/getRuntime)
                  (.exec (into-array String ["/bin/sh" "-c" "stty -g < /dev/tty"]))
                  (doto .waitFor)
                  .getInputStream
                  slurp
                  str/trim)
        tty   (java.io.FileInputStream. "/dev/tty")]
    (try
      (-> (Runtime/getRuntime)
          (.exec (into-array String ["/bin/sh" "-c" "stty raw -echo < /dev/tty"]))
          .waitFor)
      (f tty)
      (finally
        (.close tty)
        (-> (Runtime/getRuntime)
            (.exec (into-array String ["/bin/sh" "-c" (str "stty " saved " < /dev/tty")]))
            .waitFor)))))

(defn select
  "Display a selection menu. Options is a vec of strings or maps with :label.
   Returns the selected option (string or map). Non-interactive: returns first option."
  [message options]
  (if-not (tui/interactive?)
    (do (binding [*out* *err*]
          (println (str "WARNING: Non-interactive, auto-selecting: "
                        (if (map? (first options))
                          (:label (first options))
                          (first options)))))
        (first options))
    (let [labels (mapv #(if (map? %) (:label %) %) options)
          n      (count options)]
      (tui/eprintln (style/bold (str "? " message)))
      (tui/eprint (style/hide-cursor))
      (try
        (with-raw-mode
          (fn [tty]
            (loop [selected 0]
              (doseq [i (range n)]
                (tui/eprint (str (if (= i selected)
                                   (str (style/cyan "▸ ") (style/bold (nth labels i)))
                                   (str "  " (nth labels i)))
                                 "\r\n")))
              (let [ch (.read tty)]
                ;; Erase the menu lines for redraw
                (tui/eprint (str (style/cursor-up n)
                                 (str/join (repeat n (str (style/clear-line) "\r\n")))
                                 (style/cursor-up n)))
                (cond
                  (#{key-enter key-cr} ch)
                  (let [chosen (nth options selected)]
                    (when-not (= :back (:value chosen))
                      (tui/eprint (str (style/green "✓ ") message " → "
                                       (style/cyan (nth labels selected)) "\r\n")))
                    chosen)

                  (= key-escape ch)
                  (case (read-arrow! tty)
                    :up   (recur (mod (dec selected) n))
                    :down (recur (mod (inc selected) n))
                    ;; Bare ESC (no follow-up bytes) — treat as cancel,
                    ;; same as Q / Ctrl-C. Used to hang here forever.
                    nil   nil)

                  (= key-k ch) (recur (mod (dec selected) n))
                  (= key-j ch) (recur (mod (inc selected) n))

                  (#{key-q key-ctrl-c} ch) nil

                  :else (recur selected))))))
        (finally
          (tui/eprint (style/show-cursor)))))))
