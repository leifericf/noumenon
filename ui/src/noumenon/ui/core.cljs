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

(defn- maybe-init-graph!
  "When graph data is ready, build nodes/edges and start simulation."
  [state]
  (let [{:keys [graph/hotspots graph/layer-data graph/edge-data
                graph/filter-dir graph/filter-layer]} state]
    (when (and (seq hotspots) edge-data)
      (let [all-nodes  (gdata/build-nodes hotspots layer-data)
            raw-edges  (gdata/build-edges edge-data)
            edge-files (into #{} (mapcat (fn [{:keys [source target]}] [source target])) raw-edges)
            known-ids  (set (map :id all-nodes))
            extra-nodes (mapv (fn [id] {:id id :group nil :size 1 :color (get gdata/layer-colors nil)})
                              (remove known-ids edge-files))
            all-with-extra (into all-nodes extra-nodes)
            nodes (cond->> all-with-extra
                    (seq filter-dir)
                    (filter #(str/starts-with? (:id %) filter-dir))
                    filter-layer
                    (filter #(= (:group %) filter-layer)))
            node-ids (set (map :id nodes))
            edges (->> raw-edges
                       (filter #(and (node-ids (:source %))
                                     (node-ids (:target %)))))]
        (state/dispatch! [:action/graph-built {:nodes nodes :edges edges}])
        (js/setTimeout
         (fn []
           (when-let [old-canvas (.querySelector js/document "canvas")]
             (let [fresh (.cloneNode old-canvas false)
                   parent (.-parentElement old-canvas)]
               (.replaceChild parent fresh old-canvas)
               (let [w (.-clientWidth parent)
                     h (.-clientHeight parent)]
                 (set! (.-width fresh) w)
                 (set! (.-height fresh) h))
               (reset! graph/canvas-ref fresh)
               (when-let [old @graph/simulation-atom]
                 (force/stop-simulation old))
               (let [transform-atom (atom nil)
                     hover-atom     (atom nil)
                     draw-fn (fn []
                               (when-let [c @graph/canvas-ref]
                                 (grender/draw! c @graph/simulation-atom
                                                {:selected-id (:graph/selected @state/app-state)
                                                 :hover-id    @hover-atom
                                                 :transform   @transform-atom
                                                 :focused-ids (:graph/focused-ids @state/app-state)})))
                     sim (force/create-simulation
                          nodes edges
                          {:width  (.-width fresh)
                           :height (.-height fresh)
                           :on-tick draw-fn})]
                 (reset! graph/simulation-atom sim)
                 ;; Zoom — use rAF to coalesce rapid zoom events into one draw
                 (let [raf-pending (atom false)]
                   (controls/setup-zoom! fresh
                                         (fn [t]
                                           (reset! transform-atom t)
                                           (when-not @raf-pending
                                             (reset! raf-pending true)
                                             (js/requestAnimationFrame
                                              (fn []
                                                (reset! raf-pending false)
                                                (draw-fn)))))))
                 (letfn [(mouse->graph [e]
                           (let [rect (.getBoundingClientRect fresh)
                                 mx   (- (.-clientX e) (.-left rect))
                                 my   (- (.-clientY e) (.-top rect))
                                 t    @transform-atom]
                             (if t
                               [(/ (- mx (.-x t)) (.-k t))
                                (/ (- my (.-y t)) (.-k t))]
                               [mx my])))]
                   (.addEventListener fresh "click"
                                      (fn [e]
                                        (let [[gx gy] (mouse->graph e)
                                              node (controls/find-nearest-node
                                                    (.nodes sim) gx gy 20)]
                                          (if node
                                            (let [id (.-id node)
                                                  ;; Screen position for floating card
                                                  rect (.getBoundingClientRect fresh)
                                                  sx (.-clientX e)
                                                  sy (.-clientY e)]
                                              (state/dispatch! [:action/graph-select-node
                                                                {:id id :x sx :y sy}]))
                                            (state/dispatch! [:action/graph-select nil])))))
                   (.addEventListener fresh "mousemove"
                                      (fn [e]
                                        (let [[gx gy] (mouse->graph e)
                                              node (controls/find-nearest-node
                                                    (.nodes sim) gx gy 15)
                                              new-id (when node (.-id node))]
                                          (when (not= new-id @hover-atom)
                                            (reset! hover-atom new-id)
                                            (draw-fn))))))
                 ;; Redraw on state changes that affect the graph
                 (add-watch state/app-state :graph-redraw
                            (fn [_ _ old new]
                              (when (or (not= (:graph/selected old) (:graph/selected new))
                                        (not= (:graph/focused-ids old) (:graph/focused-ids new)))
                                (draw-fn))))))))
         100)))))

(defn- extract-value [dom-event]
  (.. dom-event -target -value))

(defn ^:export init []
  (styles/inject-styles!)
  (skeleton/inject-keyframes!)
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
         (state/dispatch! handler-data)))))
  ;; Watch for graph data readiness — no route guard, always active
  (add-watch state/app-state :graph-init
             (fn [_ _ old new]
               (when (or (not= (:graph/edge-data old) (:graph/edge-data new))
                         (not= (:graph/hotspots old) (:graph/hotspots new))
                         (not= (:graph/filter-dir old) (:graph/filter-dir new))
                         (not= (:graph/filter-layer old) (:graph/filter-layer new)))
                 (maybe-init-graph! new))))
  ;; Resize canvas on window resize
  (let [resize-and-redraw! (fn []
                             (when-let [canvas @graph/canvas-ref]
                               (when-let [parent (.-parentElement canvas)]
                                 (let [w (.-clientWidth parent)
                                       h (.-clientHeight parent)]
                                   (set! (.-width canvas) w)
                                   (set! (.-height canvas) h)
                                   (when-let [sim @graph/simulation-atom]
                                     (grender/draw! canvas sim
                                                    {:selected-id (:graph/selected @state/app-state)
                                                     :focused-ids (:graph/focused-ids @state/app-state)}))))))]
    (.addEventListener js/window "resize" resize-and-redraw!))
  ;; Keyboard shortcuts
  (.addEventListener js/document "keydown"
                     (fn [e]
                       (cond
                         (and (or (.-metaKey e) (.-ctrlKey e))
                              (= "k" (.-key e)))
                         (do (.preventDefault e)
                             (js/setTimeout
                              #(some-> (.querySelector js/document "input[type=text]")
                                       .focus)
                              100))

                         (= "Escape" (.-key e))
                         (do (state/dispatch! [:action/graph-select nil])
                             (state/dispatch! [:action/graph-clear-focus])))))
  ;; Initialize
  (state/dispatch! [:action/ask-init-suggestions])
  (add-watch state/app-state :render (fn [_ _ _ _] (render!)))
  (state/init!)
  (render!))
