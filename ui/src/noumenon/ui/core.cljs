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
                               (let [s @state/app-state]
                                 (grender/draw! c @graph/simulation-atom
                                                {:selected-id   (:graph/selected s)
                                                 :hover-id      @hover-atom
                                                 :transform     @transform-atom
                                                 :focused-ids   (:graph/focused-ids s)
                                                 :depth         (:graph/depth s)
                                                 :expanded-comp (:graph/expanded-comp s)
                                                 :expanded-file (:graph/expanded-file s)
                                                 :expand-time   (:graph/expand-time s)}))))
                   ;; Choose simulation type based on depth
                   sim (case (or depth :components)
                         :components (force/create-component-simulation
                                      nodes edges
                                      {:width (.-width fresh) :height (.-height fresh)
                                       :on-tick draw-fn})
                         ;; For files/segments, use regular simulation
                         (force/create-simulation
                          nodes edges
                          {:width (.-width fresh) :height (.-height fresh)
                           :on-tick draw-fn}))]
               (reset! graph/simulation-atom sim)
               ;; Zoom
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
               ;; Click — depth-aware routing
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
                                            node    (controls/find-nearest-node
                                                     (.nodes sim) gx gy 35)
                                            depth   (:graph/depth @state/app-state)]
                                        (if node
                                          (let [id    (.-id node)
                                                ntype (keyword (or (.-type node) "file"))
                                                sx    (.-clientX e)
                                                sy    (.-clientY e)]
                                            (case [depth ntype]
                                              ;; Component level — expand into files
                                              [:components :component]
                                              (state/dispatch! [:action/graph-expand-component id])
                                              ;; File level — click file → expand to segments
                                              [:files :file]
                                              (state/dispatch! [:action/graph-expand-file id])
                                              ;; File level — click another component → expand it
                                              [:files :component]
                                              (state/dispatch! [:action/graph-expand-component id])
                                              ;; Segment level — show card
                                              [:segments :segment]
                                              (state/dispatch! [:action/graph-select-node {:id id :x sx :y sy}])
                                              ;; Default — show card
                                              (state/dispatch! [:action/graph-select-node {:id id :x sx :y sy}])))
                                          ;; Click background → collapse or deselect
                                          (if (= depth :components)
                                            (state/dispatch! [:action/graph-select nil])
                                            (state/dispatch! [:action/graph-collapse]))))))
                 (.addEventListener fresh "mousemove"
                                    (fn [e]
                                      (let [[gx gy] (mouse->graph e)
                                            node    (controls/find-nearest-node
                                                     (.nodes sim) gx gy 20)
                                            new-id  (when node (.-id node))]
                                        ;; Pointer cursor when hovering a node
                                        (set! (.. fresh -style -cursor)
                                              (if new-id "pointer" "default"))
                                        (when (not= new-id @hover-atom)
                                          (reset! hover-atom new-id)
                                          (draw-fn))))))
               ;; Redraw on state changes
               (add-watch state/app-state :graph-redraw
                          (fn [_ _ old new]
                            (when (or (not= (:graph/selected old) (:graph/selected new))
                                      (not= (:graph/focused-ids old) (:graph/focused-ids new))
                                      (not= (:graph/depth old) (:graph/depth new))
                                      (not= (:graph/expanded-comp old) (:graph/expanded-comp new))
                                      (not= (:graph/expanded-file old) (:graph/expanded-file new)))
                              (draw-fn))))))))
       100))))

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
  ;; Watch for graph data readiness — rebuild simulation on nodes/edges change
  (add-watch state/app-state :graph-init
             (fn [_ _ old new]
               (when (or (not= (:graph/nodes old) (:graph/nodes new))
                         (not= (:graph/edges old) (:graph/edges new)))
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
                                                    {:selected-id  (:graph/selected @state/app-state)
                                                     :focused-ids  (:graph/focused-ids @state/app-state)
                                                     :depth        (:graph/depth @state/app-state)
                                                     :expanded-comp (:graph/expanded-comp @state/app-state)
                                                     :expanded-file (:graph/expanded-file @state/app-state)}))))))]
    (.addEventListener js/window "resize" resize-and-redraw!))
  ;; Keyboard shortcuts — Escape pops graph level
  (.addEventListener js/document "keydown"
                     (fn [e]
                       (cond
                         (and (or (.-metaKey e) (.-ctrlKey e))
                              (= "k" (.-key e)))
                         (do (.preventDefault e)
                             (js/setTimeout
                              #(some-> (.getElementById js/document "ask-input")
                                       .focus)
                              100))

                         (= "Escape" (.-key e))
                         (let [s     @state/app-state
                               depth (:graph/depth s)]
                           (cond
                             ;; First: dismiss card if open
                             (:graph/selected s)
                             (state/dispatch! [:action/graph-select nil])
                             ;; Second: clear focus if active
                             (seq (:graph/focused-ids s))
                             (state/dispatch! [:action/graph-clear-focus])
                             ;; Third: collapse level if deeper than components
                             (not= depth :components)
                             (state/dispatch! [:action/graph-collapse]))))))
  ;; Initialize
  (state/dispatch! [:action/ask-init-suggestions])
  (add-watch state/app-state :render (fn [_ _ _ _] (render!)))
  (state/init!)
  (render!))
