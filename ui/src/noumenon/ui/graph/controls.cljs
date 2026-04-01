(ns noumenon.ui.graph.controls
  (:require ["d3-zoom" :as d3-zoom]
            ["d3-selection" :as d3-sel]))

(defn setup-zoom!
  "Set up d3-zoom on a canvas element. Returns the zoom behavior.
   on-zoom is called with the transform object."
  [canvas-el on-zoom]
  (let [zoom (-> (d3-zoom/zoom)
                 (.scaleExtent (clj->js [0.1 10]))
                 (.on "zoom" (fn [event]
                               (on-zoom (.-transform event)))))]
    (-> (d3-sel/select canvas-el)
        (.call zoom))
    zoom))

(defn find-nearest-node
  "Find the node nearest to [x y] within distance.
   Component nodes have larger hit radius; segments have smaller."
  [nodes x y max-distance]
  (let [result (atom nil)
        min-dist (atom max-distance)]
    (.forEach nodes
              (fn [node]
                (let [dx   (- x (.-x node))
                      dy   (- y (.-y node))
                      dist (Math/sqrt (+ (* dx dx) (* dy dy)))
                      ntype (keyword (or (.-type node) "file"))
                      hit-r (case ntype
                              :component 35
                              :segment   10
                              max-distance)]
                  (when (and (< dist hit-r) (< dist @min-dist))
                    (reset! min-dist dist)
                    (reset! result node)))))
    @result))
