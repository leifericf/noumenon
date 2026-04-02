(ns noumenon.ui.graph.render
  "Deep space canvas renderer — three-level drill-down graph.")

(def ^:private space-bg "#0a0e17")
(def ^:private fade-duration-ms 300)
(def ^:private node-size-selected 4)
(def ^:private node-size-hovered 3.5)
(def ^:private node-size-default 2.5)
(def ^:private zoom-label-threshold 2.5)

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

;; --- Rendering phases ---

(defn- draw-edges! [ctx links focused-ids has-focus?]
  (.beginPath ctx)
  (.forEach links
            (fn [edge]
              (let [src (.-source edge)
                    tgt (.-target edge)]
                (when (or (not has-focus?)
                          (and (focused-ids (.-id src))
                               (focused-ids (.-id tgt))))
                  (.moveTo ctx (.-x src) (.-y src))
                  (.lineTo ctx (.-x tgt) (.-y tgt))))))
  (set! (.-strokeStyle ctx) "rgba(255,255,255,0.06)")
  (set! (.-lineWidth ctx) 0.5)
  (.stroke ctx))

(defn- node-opacity [selected? hovered? focused?]
  (cond selected? 1.0 hovered? 0.8 focused? 0.5 :else 0.2))

(defn- draw-nodes! [ctx nodes {:keys [selected-id hover-id focused-ids has-focus? depth fade-alpha]}]
  (.forEach nodes
            (fn [node]
              (let [id       (.-id node)
                    focused? (or (not has-focus?) (focused-ids id))
                    selected? (= id selected-id)
                    hovered? (= id hover-id)
                    ntype    (keyword (or (.-type node) "file"))
                    child?   (case depth :files (= ntype :file) :segments (= ntype :segment) false)
                    raw      (node-opacity selected? hovered? focused?)
                    opacity  (if child? (* raw fade-alpha) raw)
                    size     (cond selected? node-size-selected hovered? node-size-hovered :else node-size-default)]
                (draw-node ctx (.-x node) (.-y node) size "#ffffff" opacity)))))

(defn- node-label-text [node ntype]
  (let [name (if (= ntype :component)
               (or (.-label node) (.-id node))
               (last (.split (.-id node) "/")))
        cnt  (when (= ntype :component) (.-file_count node))]
    (if (and cnt (pos? cnt)) (str name " [" cnt "]") name)))

(defn- draw-labels! [ctx nodes {:keys [selected-id hover-id depth zoom-k]}]
  (.forEach nodes
            (fn [node]
              (let [id        (.-id node)
                    ntype     (keyword (or (.-type node) "file"))
                    selected? (= id selected-id)
                    hovered?  (= id hover-id)
                    label     (node-label-text node ntype)]
                (cond
                  (= ntype :component)
                  (draw-label ctx (.-x node) (.-y node) label
                              (cond selected? 1.0 hovered? 0.85 :else 0.5) 3)
                  (not= depth :components)
                  (draw-label ctx (.-x node) (.-y node) label
                              (cond selected? 1.0 hovered? 0.85 :else 0.35) 3)
                  selected? (draw-label ctx (.-x node) (.-y node) label 1.0 3)
                  hovered?  (draw-label ctx (.-x node) (.-y node) label 0.8 3)
                  (> zoom-k zoom-label-threshold)
                  (draw-label ctx (.-x node) (.-y node) label 0.3 3))))))

;; --- Main draw ---

(defn draw!
  "Draw the graph. Options include depth/expanded state for three-level rendering."
  [canvas simulation {:keys [selected-id hover-id transform focused-ids
                             depth expand-time]
                      :as opts}]
  (when (and canvas simulation)
    (let [ctx        (.getContext canvas "2d")
          nodes      (.nodes simulation)
          links      (-> simulation (.force "link") .links)
          has-focus? (seq focused-ids)
          zoom-k     (if transform (.-k transform) 1)
          depth      (or depth :components)
          elapsed    (- (.now js/Date) (or expand-time 0))
          fade-alpha (if (< elapsed fade-duration-ms) (/ elapsed (double fade-duration-ms)) 1.0)
          render-ctx (assoc opts :has-focus? has-focus? :zoom-k zoom-k
                            :depth depth :fade-alpha fade-alpha)]
      (set! (.-fillStyle ctx) space-bg)
      (.fillRect ctx 0 0 (.-width canvas) (.-height canvas))
      (.save ctx)
      (when transform
        (.translate ctx (.-x transform) (.-y transform))
        (.scale ctx zoom-k zoom-k))
      (draw-edges! ctx links focused-ids has-focus?)
      (draw-nodes! ctx nodes render-ctx)
      (draw-labels! ctx nodes render-ctx)
      (.restore ctx))))
