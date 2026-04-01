(ns noumenon.ui.graph.render
  "Deep space canvas renderer — optimized for smooth pan/zoom.")

(def ^:private space-bg "#0a0e17")

;; --- Drawing helpers ---

(defn- draw-dot
  "Fast path: simple filled circle for dim/small nodes."
  [ctx x y size color opacity]
  (.beginPath ctx)
  (.arc ctx x y size 0 (* 2 Math/PI))
  (set! (.-globalAlpha ctx) opacity)
  (set! (.-fillStyle ctx) color)
  (.fill ctx)
  (set! (.-globalAlpha ctx) 1.0))

(defn- draw-star
  "Glowing star with radial gradient — only for prominent nodes."
  [ctx x y size color opacity]
  ;; Outer glow
  (when (> size 4)
    (let [glow (* size 2.5)
          grad (.createRadialGradient ctx x y 0 x y glow)]
      (.addColorStop grad 0 (str color "25"))
      (.addColorStop grad 1 "transparent")
      (set! (.-globalAlpha ctx) opacity)
      (set! (.-fillStyle ctx) grad)
      (.beginPath ctx)
      (.arc ctx x y glow 0 (* 2 Math/PI))
      (.fill ctx)))
  ;; Core
  (let [grad (.createRadialGradient ctx x y 0 x y size)]
    (.addColorStop grad 0 (str color "ff"))
    (.addColorStop grad 0.5 (str color "88"))
    (.addColorStop grad 1 "transparent")
    (set! (.-globalAlpha ctx) opacity)
    (set! (.-fillStyle ctx) grad)
    (.beginPath ctx)
    (.arc ctx x y size 0 (* 2 Math/PI))
    (.fill ctx))
  (set! (.-globalAlpha ctx) 1.0))

(defn- draw-edge-line
  "Simple straight line — much faster than bezier."
  [ctx src-x src-y tgt-x tgt-y opacity width]
  (.beginPath ctx)
  (.moveTo ctx src-x src-y)
  (.lineTo ctx tgt-x tgt-y)
  (set! (.-strokeStyle ctx) (str "rgba(150,180,220," opacity ")"))
  (set! (.-lineWidth ctx) width)
  (.stroke ctx))

(defn- draw-label [ctx x y text opacity]
  (when (> opacity 0.3)
    (set! (.-globalAlpha ctx) (* opacity 0.9))
    (set! (.-fillStyle ctx) "#e6edf3")
    (set! (.-font ctx) "11px -apple-system, sans-serif")
    (set! (.-textAlign ctx) "center")
    (.fillText ctx text x (- y 14))
    (set! (.-globalAlpha ctx) 1.0)))

;; --- Main draw ---

(defn draw!
  "Draw the graph. Options: {:selected-id :hover-id :transform :focused-ids}"
  [canvas simulation {:keys [selected-id hover-id transform focused-ids]}]
  (when (and canvas simulation)
    (let [ctx    (.getContext canvas "2d")
          width  (.-width canvas)
          height (.-height canvas)
          nodes  (.nodes simulation)
          links  (-> simulation (.force "link") .links)
          time   (.now js/Date)
          has-focus? (seq focused-ids)
          zoom-k (if transform (.-k transform) 1)]
      ;; Clear
      (set! (.-fillStyle ctx) space-bg)
      (.fillRect ctx 0 0 width height)
      ;; Transform
      (.save ctx)
      (when transform
        (.translate ctx (.-x transform) (.-y transform))
        (.scale ctx zoom-k zoom-k))
      ;; Edges — batch all in one path for performance
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
      (set! (.-strokeStyle ctx) (if has-focus?
                                  "rgba(150,180,220,0.2)"
                                  "rgba(150,180,220,0.06)"))
      (set! (.-lineWidth ctx) (if has-focus? 0.8 0.4))
      (.stroke ctx)
      ;; Nodes
      (.forEach nodes
                (fn [node]
                  (let [id       (.-id node)
                        color    (or (.-color node) "#334155")
                        base     (Math/max 2 (Math/min 16 (Math/sqrt (or (.-size node) 1))))
                        focused? (or (not has-focus?) (focused-ids id))
                        selected? (= id selected-id)
                        hovered? (= id hover-id)
                        ;; Gentle breathing
                        breath   (+ 1 (* 0.03 (Math/sin (+ (/ time 4000)
                                                           (* (hash id) 0.001)))))
                        opacity  (cond
                                   selected? 1.0
                                   hovered?  0.85
                                   focused?  (* 0.7 breath)
                                   :else     (* 0.1 breath))
                        size     (cond
                                   selected? (* base 1.5)
                                   hovered?  (* base 1.2)
                                   focused?  (* base breath)
                                   :else     (* base 0.4))]
                    ;; Use fast path for dim nodes, full star for prominent ones
                    (if (or selected? hovered? (and focused? (> base 3)))
                      (draw-star ctx (.-x node) (.-y node) size color opacity)
                      (draw-dot ctx (.-x node) (.-y node) size color opacity)))))
      ;; Labels
      (when selected-id
        (.forEach nodes
                  (fn [node]
                    (when (= (.-id node) selected-id)
                      (draw-label ctx (.-x node) (.-y node)
                                  (last (.split (.-id node) "/")) 1.0)))))
      (when (and hover-id (not= hover-id selected-id))
        (.forEach nodes
                  (fn [node]
                    (when (= (.-id node) hover-id)
                      (draw-label ctx (.-x node) (.-y node)
                                  (last (.split (.-id node) "/")) 0.8)))))
      ;; High zoom labels
      (when (> zoom-k 2.5)
        (.forEach nodes
                  (fn [node]
                    (let [id (.-id node)]
                      (when (or (not has-focus?) (focused-ids id))
                        (draw-label ctx (.-x node) (.-y node)
                                    (last (.split id "/")) 0.4))))))
      (.restore ctx))))
