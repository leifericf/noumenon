(ns noumenon.ui.views.graph
  (:require [noumenon.ui.styles :as styles]
            [noumenon.ui.graph.data :as gdata]))

;; Mutable refs used by core.cljs for d3 simulation lifecycle.
(defonce simulation-atom (atom nil))
(defonce canvas-ref (atom nil))

(defn- graph-empty-state [depth]
  (let [[title hint] (case depth
                       :files    ["No files in this component"
                                  "Run noum analyze to extract file details"]
                       :segments ["No segments in this file"
                                  "Run noum analyze to extract code segments"]
                       ["No components found"
                        "Run noum digest to analyze the codebase"])]
    [:div {:style {:position "absolute" :top "50%" :left "50%"
                   :transform "translate(-50%, -50%)"
                   :text-align "center" :color "rgba(255,255,255,0.3)"
                   :font-size "13px" :line-height "1.6"}}
     [:div {:style {:font-size "16px" :margin-bottom "8px"
                    :color "rgba(255,255,255,0.4)"}}
      title]
     [:div hint]]))

(defn- graph-status-bar [nodes edges depth expanded-comp breadcrumb]
  [:div {:style {:position "absolute" :bottom "8px" :left "12px"
                 :font-size "10px" :color "rgba(255,255,255,0.35)"}}
   (case depth
     :components (str (count (filter #(= :component (:type %)) nodes))
                      " components, " (count edges) " dependencies")
     :files      (str expanded-comp ": "
                      (count (filter #(= :file (:type %)) nodes)) " files")
     :segments   (str (last breadcrumb) ": "
                      (count (filter #(= :segment (:type %)) nodes)) " segments")
     (str (count nodes) " nodes"))])

(defn- shorten-crumb [crumb]
  (let [parts (.split (str crumb) "/")]
    (if (> (count parts) 2)
      (str (.join (.slice parts -2) "/"))
      (str crumb))))

(defn- graph-breadcrumb [breadcrumb]
  [:div {:style {:position "absolute" :top "12px" :left "16px"
                 :font-size "11px" :color "rgba(255,255,255,0.4)"
                 :display "flex" :align-items "center" :gap "4px"
                 :z-index 5}}
   [:span {:style {:cursor "pointer" :color "rgba(96,165,250,0.7)"}
           :on {:click [:action/graph-collapse-to-top]}
           :role "button" :tabindex 0
           :aria-label "Navigate to Components view"}
    "Components"]
   (let [last-idx (dec (count breadcrumb))]
     (for [[i crumb] (map-indexed vector breadcrumb)]
       [:span {:key (str "bc-" i)
               :style {:display "inline-flex" :align-items "center" :gap "4px"}}
        [:span {:style {:color "rgba(255,255,255,0.2)"}} " > "]
        [:span (cond-> {:style {:color (if (= i last-idx)
                                         "rgba(255,255,255,0.6)"
                                         "rgba(96,165,250,0.7)")
                                :cursor (when (< i last-idx) "pointer")}}
                 (< i last-idx)
                 (merge {:on {:click [:action/graph-collapse]}
                         :role "button" :tabindex 0
                         :aria-label (str "Navigate to " crumb)}))
         (shorten-crumb crumb)]]))])

(defn graph-canvas
  "Full-viewport graph canvas with depth-aware status and breadcrumb."
  [state]
  (let [{:keys [graph/nodes graph/edges graph/loading?
                graph/depth graph/expanded-comp graph/breadcrumb]} state
        depth (or depth :components)]
    [:div {:style {:position "absolute" :inset 0 :z-index 0}}
     [:canvas {:style {:width "100%" :height "100%" :display "block"}}]
     (when loading?
       [:div {:style {:position "absolute" :top "50%" :left "50%"
                      :transform "translate(-50%, -50%)"
                      :font-size "13px" :color "rgba(255,255,255,0.5)"
                      :text-align "center"}}
        "Loading graph..."])
     (when (and (not loading?) (not (seq nodes)))
       (graph-empty-state depth))
     (when (seq nodes)
       (graph-status-bar nodes edges depth expanded-comp breadcrumb))
     (when (seq breadcrumb)
       (graph-breadcrumb breadcrumb))]))
