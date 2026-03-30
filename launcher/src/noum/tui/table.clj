(ns noum.tui.table
  "Column-aligned table output."
  (:require [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn render
  "Print a table to stderr. headers is a vec of strings, rows is a vec of vecs."
  [headers rows]
  (let [all-rows (cons headers rows)
        widths   (reduce (fn [ws row]
                           (mapv (fn [w cell] (max w (count (str cell))))
                                 ws row))
                         (vec (repeat (count headers) 0))
                         all-rows)
        fmt-row  (fn [row]
                   (str/join "  "
                             (map-indexed (fn [i cell]
                                            (format (str "%-" (nth widths i) "s") (str cell)))
                                          row)))]
    (tui/eprintln (style/bold (fmt-row headers)))
    (tui/eprintln (str/join "  " (map #(apply str (repeat % (if (tui/utf8?) "─" "-"))) widths)))
    (doseq [row rows]
      (tui/eprintln (fmt-row row)))))
