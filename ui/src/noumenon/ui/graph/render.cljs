(ns noumenon.ui.graph.render
  "Deep space canvas renderer — three-level drill-down graph.")

(def ^:private space-bg "#0a0e17")

;; --- Drawing helpers ---

(defn- draw-node
  "Filled circle — uniform style for all node types."
  [ctx x y size color opacity]
  (.beginPath ctx)
  (.arc ctx x y size 0 (* 2 Math/PI))
  (set! (.-globalAlpha ctx) opacity)
  (set! (.-fillStyle ctx) color)
  (.fill ctx)
  (set! (.-globalAlpha ctx) 1.0))

(defn- draw-label [ctx x y text opacity node-size]
  (when (> opacity 0.3)
    (set! (.-globalAlpha ctx) (* opacity 0.75))
    (set! (.-fillStyle ctx) "#c0c8d4")
    (set! (.-font ctx) "300 9px -apple-system, sans-serif")
    (set! (.-textAlign ctx) "center")
    (.fillText ctx text x (+ y (or node-size 6) 10))
    (set! (.-globalAlpha ctx) 1.0)))

;; --- Main draw ---

(defn draw!
  "Draw the graph. Options include depth/expanded state for three-level rendering."
  [canvas simulation {:keys [selected-id hover-id transform focused-ids
                             depth expanded-comp expanded-file people
                             expand-time]}]
  (when (and canvas simulation)
    (let [ctx    (.getContext canvas "2d")
          width  (.-width canvas)
          height (.-height canvas)
          nodes  (.nodes simulation)
          links  (-> simulation (.force "link") .links)
          time   (.now js/Date)
          has-focus? (seq focused-ids)
          zoom-k (if transform (.-k transform) 1)
          depth  (or depth :components)
          ;; Fade-in for expanded children (300ms)
          fade-alpha (if (and expand-time (< (- time expand-time) 300))
                       (/ (- time expand-time) 300.0)
                       1.0)]
      ;; Clear
      (set! (.-fillStyle ctx) space-bg)
      (.fillRect ctx 0 0 width height)
      ;; Transform
      (.save ctx)
      (when transform
        (.translate ctx (.-x transform) (.-y transform))
        (.scale ctx zoom-k zoom-k))
      ;; Edges
      (.beginPath ctx)
      (.forEach links
                (fn [edge]
                  (let [src (.-source edge)
                        tgt (.-target edge)
                        both-focused? (and has-focus?
                                           (focused-ids (.-id src))
                                           (focused-ids (.-id tgt)))]
                    (when (or (not has-focus?) both-focused?)
                      (.moveTo ctx (.-x src) (.-y src))
                      (.lineTo ctx (.-x tgt) (.-y tgt))))))
      (set! (.-strokeStyle ctx) "rgba(255,255,255,0.06)")
      (set! (.-lineWidth ctx) 0.5)
      (.stroke ctx)
      ;; Nodes — all white, uniform small size
      (.forEach nodes
                (fn [node]
                  (let [id       (.-id node)
                        focused? (or (not has-focus?) (focused-ids id))
                        selected? (= id selected-id)
                        hovered? (= id hover-id)
                        ntype    (keyword (or (.-type node) "file"))
                        child?   (case depth
                                   :files    (= ntype :file)
                                   :segments (= ntype :segment)
                                   false)
                        raw-opacity (cond
                                      selected?  1.0
                                      hovered?   0.8
                                      focused?   0.5
                                      :else      0.2)
                        opacity  (if child? (* raw-opacity fade-alpha) raw-opacity)
                        size     (cond selected? 4 hovered? 3.5 :else 2.5)]
                    (draw-node ctx (.-x node) (.-y node) size "#ffffff" opacity))))
      ;; Labels
      (.forEach nodes
                (fn [node]
                  (let [id    (.-id node)
                        ntype (keyword (or (.-type node) "file"))
                        selected? (= id selected-id)
                        hovered?  (= id hover-id)
                        name  (if (= ntype :component)
                                (or (.-label node) id)
                                (last (.split id "/")))
                        ;; Child count in brackets — components show file count
                        cnt   (when (= ntype :component) (.-file_count node))
                        label (if (and cnt (pos? cnt))
                                (str name " [" cnt "]")
                                name)]
                    (cond
                      (= ntype :component)
                      (draw-label ctx (.-x node) (.-y node) label
                                  (cond selected? 1.0 hovered? 0.85 :else 0.5) 3)
                      (not= depth :components)
                      (draw-label ctx (.-x node) (.-y node) label
                                  (cond selected? 1.0 hovered? 0.85 :else 0.35) 3)
                      selected? (draw-label ctx (.-x node) (.-y node) label 1.0 3)
                      hovered?  (draw-label ctx (.-x node) (.-y node) label 0.8 3)
                      (> zoom-k 2.5) (draw-label ctx (.-x node) (.-y node) label 0.3 3)))))
      (.restore ctx))))
