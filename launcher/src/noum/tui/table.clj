(ns noum.tui.table
  "Column-aligned table output."
  (:require [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn- visible-length
  "String length excluding ANSI escape sequences."
  [s]
  (count (str/replace (str s) #"\033\[[0-9;]*m" "")))

(defn render
  "Print a table to stderr. headers is a vec of strings, rows is a vec of vecs."
  [headers rows]
  (let [all-rows (cons headers rows)
        widths   (reduce (fn [ws row]
                           (mapv (fn [w cell] (max w (visible-length (str cell))))
                                 ws row))
                         (vec (repeat (count headers) 0))
                         all-rows)
        fmt-row  (fn [row]
                   (str/join "  "
                             (map-indexed (fn [i cell]
                                            (let [s (str cell)
                                                  pad (- (nth widths i) (visible-length s))]
                                              (str s (apply str (repeat (max 0 pad) " ")))))
                                          row)))]
    (tui/eprintln (style/bold (fmt-row headers)))
    (tui/eprintln (str/join "  " (map #(apply str (repeat % (if (tui/utf8?) "─" "-"))) widths)))
    (doseq [row rows]
      (tui/eprintln (fmt-row row)))))
