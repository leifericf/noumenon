(ns noumenon.ui.views.shell
  (:require [noumenon.ui.components.toast :as toast]
            [noumenon.ui.views.ask :as ask]
            [noumenon.ui.views.graph :as graph]
            [noumenon.ui.styles :as styles]))

(defn app-shell [state]
  (let [{:keys [toasts daemon/status databases/list db-name
                graph/selected graph/node-card graph/node-card-pos
                graph/nodes]} state]
    [:div {:style {:position "relative"
                   :width "100vw"
                   :height "100vh"
                   :overflow "hidden"
                   :background "#0a0e17"}}
     ;; Graph — full viewport background
     (graph/graph-canvas state)
     ;; Ask — floating overlay
     [:div {:style {:position "absolute"
                    :top 0
                    :left "50%"
                    :transform "translateX(-50%)"
                    :width "100%"
                    :max-width "720px"
                    :max-height "100vh"
                    :overflow-y "auto"
                    :padding "24px"
                    :pointer-events "none"
                    :z-index 10}}
      [:div {:style {:pointer-events "auto"}}
       (ask/ask-view state)]]
     ;; Node info card — inline to avoid linter issues
     (when (and selected node-card-pos)
       (let [node   (->> nodes (filter #(= (:id %) selected)) first)
             layer  (some-> node :group name)
             churn  (:size node)
             left   (min (- js/window.innerWidth 320) (max 16 (:x node-card-pos)))
             top    (min (- js/window.innerHeight 300) (max 16 (+ (:y node-card-pos) 20)))
             imports    (:imports node-card)
             importers  (:importers node-card)
             authors    (:authors node-card)
             summary    (:summary node-card)
             complexity (:complexity node-card)
             history    (:history node-card)]
         [:div {:style {:position "fixed"
                        :left (str left "px")
                        :top (str top "px")
                        :width "280px"
                        :max-height "70vh"
                        :overflow-y "auto"
                        :background "rgba(12,16,24,0.92)"
                        :backdrop-filter "blur(16px)"
                        :-webkit-backdrop-filter "blur(16px)"
                        :border "1px solid rgba(255,255,255,0.08)"
                        :border-radius "12px"
                        :padding "16px"
                        :z-index 50
                        :pointer-events "auto"
                        :animation "fadeSlideIn 0.2s ease"}}
          ;; File path
          [:div {:style {:font-family "'SF Mono', monospace"
                         :font-size "12px"
                         :color "#e6edf3"
                         :word-break "break-all"
                         :margin-bottom "10px"
                         :font-weight 600}}
           selected]
          ;; Quick stats
          [:div {:style {:display "flex" :gap "12px" :margin-bottom "10px"
                         :font-size "11px"}}
           (when layer
             [:span {:style {:padding "2px 8px"
                             :border-radius "10px"
                             :background (str (or (:color node) "#334155") "20")
                             :color (or (:color node) "#94a3b8")}}
              layer])
           (when (and churn (> churn 1))
             [:span {:style {:color "rgba(255,255,255,0.4)"}}
              (str churn " commits")])
           (when (and complexity (not= complexity "unknown") (not= complexity :unknown))
             (let [c (name complexity)]
               [:span {:style {:padding "2px 8px"
                               :border-radius "10px"
                               :font-size "10px"
                               :background (case c
                                             "very-complex" "rgba(248,81,73,0.15)"
                                             "complex"      "rgba(210,153,34,0.15)"
                                             "moderate"     "rgba(210,153,34,0.1)"
                                             "rgba(255,255,255,0.05)")
                               :color (case c
                                        "very-complex" "#f85149"
                                        "complex"      "#d29922"
                                        "moderate"     "#d29922"
                                        "rgba(255,255,255,0.4)")}}
                c]))]
          ;; Summary
          (when (seq summary)
            [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.5)"
                           :line-height "1.4" :margin-bottom "10px"
                           :font-style "italic"}}
             (subs summary 0 (min 200 (count summary)))])
          ;; Recent changes
          (when (seq history)
            [:div {:style {:margin-bottom "8px"}}
             [:div {:style {:font-size "10px" :color "rgba(255,255,255,0.3)"
                            :text-transform "uppercase" :letter-spacing "0.5px"
                            :margin-bottom "4px"}}
              "Recent changes"]
             [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.4)"
                            :line-height "1.5"}}
              (for [[i row] (map-indexed vector (take 3 history))]
                (let [msg (second row)]
                  [:div {:key i :style {:white-space "nowrap" :overflow "hidden"
                                        :text-overflow "ellipsis"}}
                   (when msg (subs msg 0 (min 50 (count msg))))]))]])
          ;; Imports
          (when (seq imports)
            [:div {:style {:margin-bottom "8px"}}
             [:div {:style {:font-size "10px" :color "rgba(255,255,255,0.3)"
                            :text-transform "uppercase" :letter-spacing "0.5px"
                            :margin-bottom "4px"}}
              (str "Imports (" (count imports) ")")]
             [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.5)"
                            :line-height "1.6"}}
              (for [[i row] (map-indexed vector (take 5 imports))]
                [:div {:key i} (first row)])
              (when (> (count imports) 5)
                [:span {:style {:color "rgba(255,255,255,0.25)"}}
                 (str "+" (- (count imports) 5) " more")])]])
          ;; Imported by
          (when (seq importers)
            [:div {:style {:margin-bottom "8px"}}
             [:div {:style {:font-size "10px" :color "rgba(255,255,255,0.3)"
                            :text-transform "uppercase" :letter-spacing "0.5px"
                            :margin-bottom "4px"}}
              (str "Imported by (" (count importers) ")")]
             [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.5)"
                            :line-height "1.6"}}
              (for [[i row] (map-indexed vector (take 5 importers))]
                [:div {:key i} (first row)])
              (when (> (count importers) 5)
                [:span {:style {:color "rgba(255,255,255,0.25)"}}
                 (str "+" (- (count importers) 5) " more")])]])
          ;; Authors
          (when (seq authors)
            [:div {:style {:margin-bottom "8px"}}
             [:div {:style {:font-size "10px" :color "rgba(255,255,255,0.3)"
                            :text-transform "uppercase" :letter-spacing "0.5px"
                            :margin-bottom "4px"}}
              "Authors"]
             [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.5)"
                            :line-height "1.6"}}
              (for [[i row] (map-indexed vector (take 3 authors))]
                [:div {:key i} (str (first row) " (" (second row) " commits)")])]])]))
     ;; Database selector — top right
     (when (and (seq list) (> (count list) 1))
       [:select {:on {:change [:action/select-db]}
                 :value (or db-name "")
                 :style {:position "fixed"
                         :top "12px"
                         :right "16px"
                         :background "rgba(10,14,23,0.7)"
                         :border "1px solid rgba(255,255,255,0.1)"
                         :border-radius (:radius styles/tokens)
                         :color (:text-muted styles/tokens)
                         :padding "4px 8px"
                         :font-size "11px"
                         :pointer-events "auto"}}
        (for [{:keys [name]} list]
          [:option {:key name :value name} name])])
     ;; Toasts
     (toast/toast-container toasts)
     ;; Connection status
     [:div {:style {:position "fixed"
                    :bottom "8px"
                    :right "12px"
                    :display "flex"
                    :align-items "center"
                    :gap "5px"
                    :font-size "10px"
                    :color (:text-muted styles/tokens)}}
      [:div {:style {:width "5px" :height "5px" :border-radius "50%"
                     :background (case status
                                   :connected "#3fb950"
                                   :error     "#f85149"
                                   (:text-muted styles/tokens))}}]
      (case status
        :connected "Connected"
        :error     "Disconnected"
        "Connecting...")]]))
