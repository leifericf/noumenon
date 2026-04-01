(ns noumenon.ui.graph.force
  (:require ["d3-force" :as d3]))

(defn create-simulation
  "Create a d3-force simulation with gentle forces.
   on-tick is called during the warm-up phase only."
  [nodes edges {:keys [on-tick width height]}]
  (let [cx  (/ (or width 800) 2)
        cy  (/ (or height 600) 2)
        sim (-> (d3/forceSimulation (clj->js nodes))
                (.force "link"
                        (-> (d3/forceLink (clj->js edges))
                            (.id (fn [d] (.-id d)))
                            (.distance 80)
                            (.strength 0.3)))
                (.force "charge"
                        (-> (d3/forceManyBody)
                            (.strength -80)
                            (.distanceMax 250)))
                (.force "center"
                        (d3/forceCenter cx cy))
                (.force "collide"
                        (-> (d3/forceCollide)
                            (.radius 6)))
                ;; Faster cooldown — settle quickly then stop
                (.alpha 0.8)
                (.alphaDecay 0.03)
                (.velocityDecay 0.4)
                (.on "tick" (or on-tick identity)))]
    sim))

(defn stop-simulation [sim]
  (when sim (.stop sim)))

(defn reheat-simulation [sim]
  (when sim
    (-> sim (.alpha 0.3) (.restart))))
