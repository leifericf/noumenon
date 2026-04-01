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

(defn create-component-simulation
  "Simulation tuned for 10-30 component nodes.
   Moderate spacing, centered layout."
  [nodes edges {:keys [on-tick width height]}]
  (let [cx  (/ (or width 800) 2)
        cy  (/ (or height 600) 2)
        sim (-> (d3/forceSimulation (clj->js nodes))
                (.force "link"
                        (-> (d3/forceLink (clj->js edges))
                            (.id (fn [d] (.-id d)))
                            (.distance 100)
                            (.strength 0.25)))
                (.force "charge"
                        (-> (d3/forceManyBody)
                            (.strength -100)
                            (.distanceMax 250)))
                (.force "center"
                        (d3/forceCenter cx cy))
                ;; Pull all nodes toward center — stronger to rein in outliers
                (.force "x" (-> (d3/forceX cx) (.strength 0.1)))
                (.force "y" (-> (d3/forceY cy) (.strength 0.1)))
                (.force "collide"
                        (-> (d3/forceCollide)
                            (.radius 25)))
                (.alpha 0.8)
                (.alphaDecay 0.03)
                (.velocityDecay 0.4)
                (.on "tick" (or on-tick identity)))]
    sim))

(defn create-cluster-simulation
  "Localized simulation for a cluster of child nodes.
   Anchored near (cx, cy), compact layout."
  [nodes edges {:keys [cx cy on-tick]}]
  (-> (d3/forceSimulation (clj->js nodes))
      (.force "link"
              (-> (d3/forceLink (clj->js edges))
                  (.id (fn [d] (.-id d)))
                  (.distance 50)
                  (.strength 0.4)))
      (.force "charge"
              (-> (d3/forceManyBody)
                  (.strength -60)
                  (.distanceMax 200)))
      (.force "center"
              (d3/forceCenter cx cy))
      (.force "collide"
              (-> (d3/forceCollide)
                  (.radius 8)))
      (.alpha 0.6)
      (.alphaDecay 0.05)
      (.velocityDecay 0.5)
      (.on "tick" (or on-tick identity))))

(defn stop-simulation [sim]
  (when sim (.stop sim)))

(defn freeze-simulation
  "Stop ticking but preserve node positions."
  [sim]
  (when sim (.stop sim)))

(defn reheat-simulation [sim]
  (when sim
    (-> sim (.alpha 0.3) (.restart))))
