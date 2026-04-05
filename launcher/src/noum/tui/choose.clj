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
                  (let [_ (.read tty)
                        arrow (.read tty)]
                    (recur (cond
                             (= arrow 65) (mod (dec selected) n)
                             (= arrow 66) (mod (inc selected) n)
                             :else selected)))

                  (= key-k ch) (recur (mod (dec selected) n))
                  (= key-j ch) (recur (mod (inc selected) n))

                  (#{key-q key-ctrl-c} ch) nil

                  :else (recur selected))))))
        (finally
          (tui/eprint (style/show-cursor)))))))
