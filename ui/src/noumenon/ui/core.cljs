(ns noumenon.ui.core
  (:require [clojure.string :as str]
            [replicant.dom :as r]
            [noumenon.ui.state :as state]
            [noumenon.ui.styles :as styles]
            [noumenon.ui.views.shell :as shell]
            [noumenon.ui.views.graph :as graph]
            [noumenon.ui.graph.data :as gdata]
            [noumenon.ui.graph.force :as force]
            [noumenon.ui.graph.render :as grender]
            [noumenon.ui.graph.controls :as controls]
            [noumenon.ui.components.skeleton :as skeleton]))

(defn render! []
  (r/render (js/document.getElementById "app")
            (shell/app-shell @state/app-state)))

(defn ^:dev/after-load reload! []
  (render!))

(def ^:private click-hit-distance 35)
(def ^:private hover-base-distance 20)
(def ^:private min-zoom-scale 0.1)
(def ^:private graph-init-delay-ms 100)
(defonce ^:private draw-fn-atom (atom nil))

(defn- create-draw-fn
  "Create a draw callback that renders the current graph state."
  [hover-atom transform-atom]
  (fn []
    (when-let [c @graph/canvas-ref]
      (let [s @state/app-state]
        (grender/draw! c @graph/simulation-atom
                       {:selected-id   (:graph/selected s)
                        :hover-id      @hover-atom
                        :transform     @transform-atom
                        :focused-ids   (:graph/focused-ids s)
                        :depth         (:graph/depth s)
                        :expanded-comp (:graph/expanded-comp s)
                        :expanded-file (:graph/expanded-file s)
                        :expand-time   (:graph/expand-time s)})))))

(defn- setup-zoom-handler!
  "Attach zoom/pan controls to a canvas element."
  [canvas transform-atom draw-fn]
  (let [raf-pending (atom false)]
    (controls/setup-zoom! canvas
                          (fn [t]
                            (reset! transform-atom t)
                            (when-not @raf-pending
                              (reset! raf-pending true)
                              (js/requestAnimationFrame
                               (fn []
                                 (reset! raf-pending false)
                                 (draw-fn))))))))

(defn- handle-canvas-click
  "Handle click on canvas: select node or clear selection."
  [canvas sim transform-atom e]
  (let [rect  (.getBoundingClientRect canvas)
        mx    (- (.-clientX e) (.-left rect))
        my    (- (.-clientY e) (.-top rect))
        t     @transform-atom
        [gx gy] (if t
                  [(/ (- mx (.-x t)) (.-k t)) (/ (- my (.-y t)) (.-k t))]
                  [mx my])
        node  (controls/find-nearest-node (.nodes sim) gx gy click-hit-distance)
        s     @state/app-state
        depth (:graph/depth s)]
    (if node
      (let [id    (.-id node)
            ntype (keyword (or (.-type node) "file"))
            sx    (.-clientX e)
            sy    (.-clientY e)
            gx    (.-x node)
            gy    (.-y node)]
        (when-not (:graph/loading? s)
          (case [depth ntype]
            [:components :component]
            (state/dispatch! [:action/graph-expand-component {:id id :cx gx :cy gy}])
            [:files :file]
            (state/dispatch! [:action/graph-expand-file {:id id :cx gx :cy gy}])
            [:files :component]
            (state/dispatch! [:action/graph-expand-component {:id id :cx gx :cy gy}])
            [:segments :segment]
            (state/dispatch! [:action/graph-select-node {:id id :x sx :y sy}])
            (state/dispatch! [:action/graph-select-node {:id id :x sx :y sy}]))))
      (cond
        (seq (:graph/focused-ids s))
        (state/dispatch! [:action/graph-clear-focus])
        (= depth :components)
        (state/dispatch! [:action/graph-select nil])
        :else
        (state/dispatch! [:action/graph-collapse])))))

(defn- handle-canvas-mousemove
  "Handle mousemove on canvas: update hover state and cursor."
  [canvas sim hover-atom transform-atom draw-fn e]
  (let [rect  (.getBoundingClientRect canvas)
        mx    (- (.-clientX e) (.-left rect))
        my    (- (.-clientY e) (.-top rect))
        t     @transform-atom
        [gx gy] (if t
                  [(/ (- mx (.-x t)) (.-k t)) (/ (- my (.-y t)) (.-k t))]
                  [mx my])
        zoom-k   (if t (.-k t) 1)
        hit-dist (/ hover-base-distance (max min-zoom-scale zoom-k))
        node     (controls/find-nearest-node (.nodes sim) gx gy hit-dist)
        new-id   (when node (.-id node))]
    (set! (.. canvas -style -cursor) (if new-id "pointer" "default"))
    (set! (.-title canvas) (or new-id ""))
    (when (not= new-id @hover-atom)
      (reset! hover-atom new-id)
      (draw-fn))))

(defn- setup-graph-redraw-watch!
  "Register once: redraw the graph canvas when relevant state keys change.
   Reads the current draw-fn from draw-fn-atom so the watch never goes stale."
  []
  (add-watch state/app-state :graph-redraw
             (fn [_ _ old new]
               (when (or (not= (:graph/selected old) (:graph/selected new))
                         (not= (:graph/focused-ids old) (:graph/focused-ids new))
                         (not= (:graph/depth old) (:graph/depth new))
                         (not= (:graph/expanded-comp old) (:graph/expanded-comp new))
                         (not= (:graph/expanded-file old) (:graph/expanded-file new)))
                 (when-let [f @draw-fn-atom]
                   (f))))))

(defn- maybe-init-graph!
  "When graph nodes/edges change, rebuild simulation and canvas."
  [state]
  (let [{:keys [graph/nodes graph/edges graph/depth]} state]
    (when (seq nodes)
      (js/setTimeout
       (fn []
         (when-let [old-canvas (.querySelector js/document "canvas")]
           (let [fresh  (.cloneNode old-canvas false)
                 parent (.-parentElement old-canvas)]
             (.replaceChild parent fresh old-canvas)
             (let [w (.-clientWidth parent) h (.-clientHeight parent)]
               (set! (.-width fresh) w)
               (set! (.-height fresh) h))
             (reset! graph/canvas-ref fresh)
             (when-let [old @graph/simulation-atom]
               (force/stop-simulation old))
             (let [transform-atom (atom nil)
                   hover-atom     (atom nil)
                   draw-fn        (create-draw-fn hover-atom transform-atom)
                   w (.-width fresh) h (.-height fresh)
                   sim (case (or depth :components)
                         :components (force/create-component-simulation
                                      nodes edges {:width w :height h :on-tick draw-fn})
                         (force/create-cluster-simulation
                          nodes edges {:cx (/ w 2) :cy (/ h 2) :on-tick draw-fn}))]
               (reset! graph/simulation-atom sim)
               (setup-zoom-handler! fresh transform-atom draw-fn)
               (.addEventListener fresh "click"
                                  (partial handle-canvas-click fresh sim transform-atom))
               (.addEventListener fresh "mousemove"
                                  (partial handle-canvas-mousemove fresh sim hover-atom
                                           transform-atom draw-fn))
               (reset! draw-fn-atom draw-fn)))))
       graph-init-delay-ms))))

(defn- extract-value [dom-event]
  (.. dom-event -target -value))

(defn- setup-replicant-dispatcher!
  "Wire up Replicant DOM event dispatch to app state handlers."
  []
  (r/set-dispatch!
   (fn [replicant-data handler-data]
     (let [dom-event (:replicant/dom-event replicant-data)]
       (case (first handler-data)
         :action/ask-input
         (state/dispatch! [:action/ask-set-query (extract-value dom-event)])

         :action/ask-keydown
         (let [completions (:ask/completions @state/app-state)
               key         (.-key dom-event)]
           (cond
             (and (seq completions) (= "ArrowDown" key))
             (do (.preventDefault dom-event)
                 (state/dispatch! [:action/ask-completion-nav :down]))

             (and (seq completions) (= "ArrowUp" key))
             (do (.preventDefault dom-event)
                 (state/dispatch! [:action/ask-completion-nav :up]))

             (and (seq completions) (= "Enter" key))
             (do (.preventDefault dom-event)
                 (let [idx (or (:ask/completion-idx @state/app-state) 0)
                       item (nth completions idx nil)]
                   (when item
                     (state/dispatch! [:action/ask-complete (:value item)]))))

             (and (seq completions) (= "Escape" key))
             (state/dispatch! [:action/ask-dismiss-completions])

             (= "Enter" key)
             (state/dispatch! [:action/ask-submit])))

         :action/select-db
         (do (state/dispatch! [:action/select-db-value (extract-value dom-event)])
             (state/dispatch! [:action/graph-load]))

         :action/schema-filter
         (state/dispatch! [:action/schema-filter-set (extract-value dom-event)])

         :action/query-editor-input
         (state/dispatch! [:action/query-editor-set (extract-value dom-event)])

         :action/query-param-input
         (let [param-name (second handler-data)]
           (state/dispatch! [:action/query-param-set param-name
                             (extract-value dom-event)]))

         :action/db-new-repo-input
         (state/dispatch! [:action/db-new-repo-set (extract-value dom-event)])

         :action/db-new-repo-keydown
         (when (= "Enter" (.-key dom-event))
           (state/dispatch! [:action/db-import-new]))

         :action/query-history-select-input
         (let [v (extract-value dom-event)]
           (when (seq v)
             (state/dispatch! [:action/query-history-select v])))

         :action/graph-filter-dir
         (state/dispatch! [:action/graph-filter-dir-set (extract-value dom-event)])

         :action/graph-filter-layer
         (state/dispatch! [:action/graph-filter-layer-set (extract-value dom-event)])

         :action/graph-switch-db
         (let [new-db (extract-value dom-event)]
           (state/dispatch! [:action/select-db-value new-db])
           (state/dispatch! [:action/graph-load]))

         :action/ask-feedback-text-input
         (state/dispatch! [:action/ask-feedback-text-set (extract-value dom-event)])

         :action/backend-switch-input
         (state/dispatch! [:action/backend-switch (extract-value dom-event)])

         ;; Default
         (state/dispatch! handler-data))))))

(defn- setup-graph-init-watch!
  "Watch for graph data readiness — rebuild simulation on nodes/edges change."
  []
  (add-watch state/app-state :graph-init
             (fn [_ _ old new]
               (when (or (not= (:graph/nodes old) (:graph/nodes new))
                         (not= (:graph/edges old) (:graph/edges new)))
                 (maybe-init-graph! new)))))

(defn- setup-resize-handler!
  "Resize canvas and redraw on window resize."
  []
  (let [resize-and-redraw!
        (fn []
          (when-let [canvas @graph/canvas-ref]
            (when-let [parent (.-parentElement canvas)]
              (let [w (.-clientWidth parent)
                    h (.-clientHeight parent)]
                (set! (.-width canvas) w)
                (set! (.-height canvas) h)
                (when-let [f @draw-fn-atom]
                  (f))))))]
    (.addEventListener js/window "resize" resize-and-redraw!)))

(defn- setup-keyboard-shortcuts!
  "Global keyboard shortcuts: Cmd+K to focus ask, Escape to pop graph."
  []
  (.addEventListener js/document "keydown"
                     (fn [e]
                       (cond
                         (and (or (.-metaKey e) (.-ctrlKey e))
                              (= "k" (.-key e)))
                         (do (.preventDefault e)
                             (js/setTimeout
                              #(some-> (.getElementById js/document "ask-input") .focus)
                              100))

                         (= "Escape" (.-key e))
                         (let [s     @state/app-state
                               depth (:graph/depth s)]
                           (cond
                             (:graph/selected s)
                             (state/dispatch! [:action/graph-select nil])
                             (seq (:graph/focused-ids s))
                             (state/dispatch! [:action/graph-clear-focus])
                             (not= depth :components)
                             (state/dispatch! [:action/graph-collapse])))))))

(defn- setup-draggable-ask-panel!
  "Make the ask panel draggable via its drag handle."
  []
  (let [drag-state (atom nil)]
    (.addEventListener js/document "mousedown"
                       (fn [e]
                         (when (some-> (.-target e) (.closest "#ask-drag-handle"))
                           (let [panel (.getElementById js/document "ask-panel")
                                 rect  (.getBoundingClientRect panel)]
                             (reset! drag-state {:offset-x (- (.-clientX e) (.-left rect))
                                                 :offset-y (- (.-clientY e) (.-top rect))})
                             (.preventDefault e)))))
    (.addEventListener js/document "mousemove"
                       (fn [e]
                         (when-let [{:keys [offset-x offset-y]} @drag-state]
                           (state/dispatch! [:action/ask-panel-move
                                             {:x (- (.-clientX e) offset-x)
                                              :y (- (.-clientY e) offset-y)}]))))
    (.addEventListener js/document "mouseup"
                       (fn [_] (reset! drag-state nil)))))

(defn ^:export init []
  (styles/inject-styles!)
  (skeleton/inject-keyframes!)
  (setup-replicant-dispatcher!)
  (setup-graph-redraw-watch!)
  (setup-graph-init-watch!)
  (setup-resize-handler!)
  (setup-keyboard-shortcuts!)
  (setup-draggable-ask-panel!)
  (state/dispatch! [:action/ask-init-suggestions])
  (add-watch state/app-state :render (fn [_ _ _ _] (render!)))
  (state/init!)
  (render!))
