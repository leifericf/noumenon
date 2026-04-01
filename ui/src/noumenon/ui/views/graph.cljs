(ns noumenon.ui.views.graph
  (:require [noumenon.ui.styles :as styles]
            [noumenon.ui.graph.data :as gdata]))

;; Mutable refs used by core.cljs for d3 simulation lifecycle.
(defonce simulation-atom (atom nil))
(defonce canvas-ref (atom nil))

(defn graph-canvas
  "Full-viewport graph canvas. Rendered as background layer."
  [state]
  (let [{:keys [graph/nodes graph/edges graph/loading?]} state]
    [:div {:style {:position "absolute"
                   :inset 0
                   :z-index 0}}
     [:canvas {:style {:width "100%" :height "100%"
                       :display "block"}}]
     (when loading?
       [:div {:style {:position "absolute"
                      :bottom "40px"
                      :left "16px"
                      :font-size "11px"
                      :color "rgba(255,255,255,0.3)"}}
        "Loading graph..."])
     (when (seq nodes)
       [:div {:style {:position "absolute"
                      :bottom "40px"
                      :left "16px"
                      :font-size "11px"
                      :color "rgba(255,255,255,0.2)"}}
        (str (count nodes) " files, " (count edges) " connections")])]))
