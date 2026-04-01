(ns noumenon.ui.graph.render
  "Deep space canvas renderer — three-level drill-down graph.")

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

(defn- draw-label [ctx x y text opacity]
  (when (> opacity 0.3)
    (set! (.-globalAlpha ctx) (* opacity 0.9))
    (set! (.-fillStyle ctx) "#e6edf3")
    (set! (.-font ctx) "11px -apple-system, sans-serif")
    (set! (.-textAlign ctx) "center")
    (.fillText ctx text x (- y 14))
    (set! (.-globalAlpha ctx) 1.0)))

;; --- Component node drawing ---

(defn- draw-component-node
  "Large circle with permanent label and glow."
  [ctx x y size color opacity label]
  ;; Outer glow
  (let [glow (* size 3)
        grad (.createRadialGradient ctx x y 0 x y glow)]
    (.addColorStop grad 0 (str color "18"))
    (.addColorStop grad 1 "transparent")
    (set! (.-globalAlpha ctx) opacity)
    (set! (.-fillStyle ctx) grad)
    (.beginPath ctx)
    (.arc ctx x y glow 0 (* 2 Math/PI))
    (.fill ctx))
  ;; Core circle
  (let [grad (.createRadialGradient ctx x y (* size 0.3) x y size)]
    (.addColorStop grad 0 (str color "cc"))
    (.addColorStop grad 0.7 (str color "66"))
    (.addColorStop grad 1 (str color "22"))
    (set! (.-globalAlpha ctx) opacity)
    (set! (.-fillStyle ctx) grad)
    (.beginPath ctx)
    (.arc ctx x y size 0 (* 2 Math/PI))
    (.fill ctx))
  ;; Label always visible
  (set! (.-globalAlpha ctx) (* opacity 0.95))
  (set! (.-fillStyle ctx) "#e6edf3")
  (set! (.-font ctx) "bold 12px -apple-system, sans-serif")
  (set! (.-textAlign ctx) "center")
  (.fillText ctx label x (+ y size 16))
  (set! (.-globalAlpha ctx) 1.0))

;; --- Segment node drawing ---

(defn- draw-segment-node
  "Tiny dot colored by quality."
  [ctx x y size color opacity]
  (.beginPath ctx)
  (.arc ctx x y (max 2 size) 0 (* 2 Math/PI))
  (set! (.-globalAlpha ctx) opacity)
  (set! (.-fillStyle ctx) color)
  (.fill ctx)
  (set! (.-globalAlpha ctx) 1.0))

;; --- Person icon drawing ---

(defn- draw-person-icon
  "Small circle with 2-letter initials."
  [ctx x y initials opacity]
  (let [r 8]
    (set! (.-globalAlpha ctx) (* opacity 0.4))
    (set! (.-fillStyle ctx) "rgba(255,255,255,0.08)")
    (set! (.-strokeStyle ctx) "rgba(255,255,255,0.15)")
    (set! (.-lineWidth ctx) 1)
    (.beginPath ctx)
    (.arc ctx x y r 0 (* 2 Math/PI))
    (.fill ctx)
    (.stroke ctx)
    (set! (.-globalAlpha ctx) (* opacity 0.5))
    (set! (.-fillStyle ctx) "#e6edf3")
    (set! (.-font ctx) "bold 8px -apple-system, sans-serif")
    (set! (.-textAlign ctx) "center")
    (set! (.-textBaseline ctx) "middle")
    (.fillText ctx initials x y)
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
                                  (case depth
                                    :components "rgba(150,180,220,0.12)"
                                    "rgba(150,180,220,0.06)")))
      (set! (.-lineWidth ctx) (case depth
                                :components 1.0
                                (if has-focus? 0.8 0.4)))
      (.stroke ctx)
      ;; Nodes — dispatch by type
      (.forEach nodes
                (fn [node]
                  (let [id       (.-id node)
                        ntype    (keyword (or (.-type node) "file"))
                        color    (or (.-color node) "#334155")
                        base     (case ntype
                                   :component (+ 15 (Math/min 20 (* 2 (Math/sqrt (or (.-size node) 1)))))
                                   :segment   (Math/max 2 (Math/min 8 (or (.-size node) 2)))
                                   (Math/max 2 (Math/min 16 (Math/sqrt (or (.-size node) 1)))))
                        focused? (or (not has-focus?) (focused-ids id))
                        selected? (= id selected-id)
                        hovered? (= id hover-id)
                        ;; Depth-aware dimming
                        dimmed?  (case depth
                                   :files    (and (= ntype :component) (not= id expanded-comp))
                                   :segments (or (and (= ntype :component) (not= id expanded-comp))
                                                 (and (= ntype :file) (not= id expanded-file)))
                                   false)
                        ;; Gentle breathing
                        breath   (+ 1 (* 0.03 (Math/sin (+ (/ time 4000)
                                                           (* (hash id) 0.001)))))
                        ;; Is this a newly-expanded child node?
                        child?   (case depth
                                   :files    (= ntype :file)
                                   :segments (= ntype :segment)
                                   false)
                        raw-opacity (cond
                                      dimmed?    (* 0.2 breath)
                                      selected?  1.0
                                      hovered?   0.85
                                      focused?   (* 0.7 breath)
                                      :else      (* 0.25 breath))
                        ;; Apply fade-in to child nodes
                        opacity  (if child? (* raw-opacity fade-alpha) raw-opacity)
                        size     (cond
                                   dimmed?    (* base 0.6)
                                   selected?  (* base 1.5)
                                   hovered?   (* base 1.2)
                                   focused?   (* base breath)
                                   :else      (* base 0.4))]
                    (case ntype
                      :component
                      (draw-component-node ctx (.-x node) (.-y node)
                                           size color opacity
                                           (or (.-label node) ""))
                      :segment
                      (draw-segment-node ctx (.-x node) (.-y node)
                                         size color opacity)
                      ;; :file and default
                      (if (or selected? hovered? (and focused? (> base 3)))
                        (draw-star ctx (.-x node) (.-y node) size color opacity)
                        (draw-dot ctx (.-x node) (.-y node) size color opacity))))))
      ;; People overlay
      (when (seq people)
        (doseq [{:keys [x y label]} people]
          (draw-person-icon ctx x y label 1.0)))
      ;; Labels for selected/hovered
      (when selected-id
        (.forEach nodes
                  (fn [node]
                    (when (= (.-id node) selected-id)
                      (let [ntype (keyword (or (.-type node) "file"))]
                        (when (not= ntype :component) ;; components already have labels
                          (draw-label ctx (.-x node) (.-y node)
                                      (last (.split (.-id node) "/")) 1.0)))))))
      (when (and hover-id (not= hover-id selected-id))
        (.forEach nodes
                  (fn [node]
                    (when (= (.-id node) hover-id)
                      (let [ntype (keyword (or (.-type node) "file"))]
                        (when (not= ntype :component)
                          (draw-label ctx (.-x node) (.-y node)
                                      (last (.split (.-id node) "/")) 0.8)))))))
      ;; High zoom labels (file/segment only)
      (when (> zoom-k 2.5)
        (.forEach nodes
                  (fn [node]
                    (let [id    (.-id node)
                          ntype (keyword (or (.-type node) "file"))]
                      (when (and (not= ntype :component)
                                 (or (not has-focus?) (focused-ids id)))
                        (draw-label ctx (.-x node) (.-y node)
                                    (last (.split id "/")) 0.4))))))
      (.restore ctx))))
