(ns noumenon.ui.views.shell
  (:require [noumenon.ui.components.toast :as toast]
            [noumenon.ui.views.ask :as ask]
            [noumenon.ui.views.graph :as graph]
            [noumenon.ui.styles :as styles]))

;; --- Card chrome (shared wrapper) ---

(defn- card-chrome [pos & children]
  (let [left (min (- js/window.innerWidth 320) (max 16 (:x pos)))
        top  (min (- js/window.innerHeight 300) (max 16 (+ (:y pos) 20)))]
    (into
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
      ;; Dismiss button
      [:div {:style {:position "absolute" :top "8px" :right "8px"
                     :width "20px" :height "20px" :border-radius "50%"
                     :display "flex" :align-items "center" :justify-content "center"
                     :cursor "pointer" :color "rgba(255,255,255,0.3)"
                     :font-size "14px" :line-height "1"}
             :on {:click [:action/graph-select nil]}
             :role "button"
             :aria-label "Close card"}
       "\u00D7"]]
     children)))

(defn- badge [text color bg]
  [:span {:style {:padding "2px 8px" :border-radius "10px"
                  :font-size "11px" :background bg :color color}}
   text])

;; --- Component card ---

(defn- component-card [node card pos]
  (card-chrome pos
               [:div {:style {:font-weight 600 :font-size "14px" :color "#e6edf3"
                              :margin-bottom "8px"}}
                (:id node)]
    ;; Badges
               [:div {:style {:display "flex" :gap "8px" :margin-bottom "10px" :flex-wrap "wrap"}}
                (when-let [layer (:group node)]
                  (badge (name layer)
                         (or (:color node) "#94a3b8")
                         (str (or (:color node) "#334155") "20")))
                (when-let [cat (:category card)]
                  (badge (name cat) "rgba(255,255,255,0.5)" "rgba(255,255,255,0.05)"))
                (when-let [c (:complexity card)]
                  (when (not= c :unknown)
                    (badge (name c)
                           (case (name c)
                             "very-complex" "#f85149"
                             "complex"      "#d29922"
                             "rgba(255,255,255,0.4)")
                           (case (name c)
                             "very-complex" "rgba(248,81,73,0.15)"
                             "complex"      "rgba(210,153,34,0.15)"
                             "rgba(255,255,255,0.05)"))))]
    ;; Summary
               (when-let [s (:summary card)]
                 (when (seq s)
                   [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.5)"
                                  :line-height "1.4" :margin-bottom "10px" :font-style "italic"}}
                    (str (subs s 0 (min 200 (count s)))
                         (when (> (count s) 200) "..."))]))
    ;; File count
               [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.4)"
                              :margin-bottom "12px"}}
                (str (:file-count card) " files")]
    ;; Call to action — clickable
               [:div {:style {:font-size "11px" :color "rgba(96,165,250,0.8)"
                              :text-align "center" :cursor "pointer"
                              :padding "6px 0" :border-top "1px solid rgba(255,255,255,0.06)"}
                      :on {:click [:action/graph-expand-component {:id (:id node)}]}
                      :role "button"}
                "Explore files"]))

;; --- Segment card ---

(defn- segment-card [node card pos]
  (card-chrome pos
    ;; Name + kind
               [:div {:style {:font-family "'SF Mono', monospace" :font-size "12px"
                              :color "#e6edf3" :font-weight 600 :margin-bottom "8px"
                              :word-break "break-all"}}
                (:id node)]
               [:div {:style {:display "flex" :gap "8px" :margin-bottom "10px" :flex-wrap "wrap"}}
                (when-let [k (:kind card)]
                  (when (not= k :unknown)
                    (badge (name k) "rgba(255,255,255,0.5)" "rgba(255,255,255,0.05)")))
                (when-let [v (:visibility card)]
                  (when (not= v :unknown)
                    (badge (name v) "rgba(255,255,255,0.4)" "rgba(255,255,255,0.04)")))
                (when (:pure? card)
                  (badge "pure" "#34d399" "rgba(52,211,153,0.12)"))
                (when-let [c (:complexity card)]
                  (when (not= c :unknown)
                    (badge (name c)
                           (case (name c) "complex" "#d29922" "rgba(255,255,255,0.4)")
                           (case (name c) "complex" "rgba(210,153,34,0.15)" "rgba(255,255,255,0.05)"))))]
    ;; Purpose
               (when-let [p (:purpose card)]
                 (when (seq p)
                   [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.5)"
                                  :line-height "1.4" :margin-bottom "10px" :font-style "italic"}}
                    (str (subs p 0 (min 200 (count p)))
                         (when (> (count p) 200) "..."))]))
    ;; Line range + args/returns
               [:div {:style {:font-size "11px" :color "rgba(255,255,255,0.35)" :margin-bottom "8px"}}
                (when (and (:line-start card) (pos? (:line-start card)))
                  (str "Lines " (:line-start card) "-" (:line-end card)))
                (when (seq (:args card))
                  (str " | " (:args card)))
                (when (seq (:returns card))
                  (str " -> " (:returns card)))]
    ;; Smells
               (when (seq (:smells card))
                 [:div {:style {:margin-bottom "8px"}}
                  [:div {:style {:font-size "10px" :color "rgba(255,255,255,0.3)"
                                 :text-transform "uppercase" :letter-spacing "0.5px"
                                 :margin-bottom "4px"}}
                   "Code smells"]
                  [:div {:style {:display "flex" :gap "4px" :flex-wrap "wrap"}}
                   (for [smell (sort (:smells card))]
                     (badge (name smell) "#fbbf24" "rgba(251,191,36,0.12)"))]])
    ;; Safety concerns
               (when (seq (:safety card))
                 [:div {:style {:margin-bottom "8px"}}
                  [:div {:style {:font-size "10px" :color "rgba(255,255,255,0.3)"
                                 :text-transform "uppercase" :letter-spacing "0.5px"
                                 :margin-bottom "4px"}}
                   "Safety concerns"]
                  [:div {:style {:display "flex" :gap "4px" :flex-wrap "wrap"}}
                   (for [concern (sort (:safety card))]
                     (badge (name concern) "#f87171" "rgba(248,113,113,0.12)"))]])
    ;; AI likelihood
               (when-let [ai (:ai-likelihood card)]
                 (when (and (not= ai :unknown) (not= ai :likely-human))
                   [:div {:style {:font-size "10px" :color "rgba(255,255,255,0.3)"
                                  :margin-top "4px"}}
                    (str "AI likelihood: " (name ai))]))))

;; --- File card (existing) ---

(defn- file-card [node card pos selected]
  (let [layer  (some-> node :group name)
        churn  (:size node)
        imports    (:imports card)
        importers  (:importers card)
        authors    (:authors card)
        summary    (:summary card)
        complexity (:complexity card)
        history    (:history card)
        ;; nil = not loaded yet; [] = loaded but empty
        loading?   (and (not (contains? card :summary))
                        (not (contains? card :imports)))]
    (card-chrome pos
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
      ;; Loading skeleton
                 (when loading?
                   [:div {:style {:color "rgba(255,255,255,0.25)" :font-size "11px"
                                  :font-style "italic" :margin-bottom "8px"}}
                    "Loading details..."])
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
                       [:div {:key i} (str (first row) " (" (second row) " commits)")])]]))))

;; --- Main shell ---

(defn app-shell [state]
  (let [{:keys [toasts daemon/status databases/list db-name
                graph/selected graph/node-card graph/node-card-pos
                graph/nodes ask/panel-x ask/panel-y]} state
        panel-centered? (nil? panel-x)]
    [:div {:style {:position "relative"
                   :width "100vw"
                   :height "100vh"
                   :overflow "hidden"
                   :background "#0a0e17"}}
     ;; Graph — full viewport background
     (graph/graph-canvas state)
     ;; Ask — draggable floating panel
     [:div {:id "ask-panel"
            :style (merge
                    {:position "absolute"
                     :width "640px"
                     :max-height "75vh"
                     :overflow-y "auto"
                     :z-index 10}
                    (if panel-centered?
                      {:top "12vh" :left "50%" :transform "translateX(-50%)"}
                      {:top (str panel-y "px") :left (str panel-x "px")}))}
      [:div {:style {:background "rgba(10,14,23,0.85)"
                     :backdrop-filter "blur(12px)"
                     :-webkit-backdrop-filter "blur(12px)"
                     :border-radius "12px"
                     :border "1px solid rgba(255,255,255,0.06)"
                     :padding "16px 20px"}}
       ;; Drag handle
       [:div {:id "ask-drag-handle"
              :style {:height "6px"
                      :cursor "grab"
                      :display "flex"
                      :justify-content "center"
                      :align-items "center"
                      :margin "-8px -8px 8px"
                      :padding "4px"
                      :border-radius "4px"}}
        [:div {:style {:width "32px" :height "3px"
                       :background "rgba(255,255,255,0.15)"
                       :border-radius "2px"}}]]
       (ask/ask-view state)]]
     ;; Node info card — type-aware
     (when (and selected node-card-pos)
       (let [node  (->> nodes (filter #(= (:id %) selected)) first)
             ctype (:type node-card)]
         (case ctype
           :component (component-card node node-card node-card-pos)
           :segment   (segment-card node node-card node-card-pos)
           (file-card node node-card node-card-pos selected))))
     ;; Database indicator/selector — top right
     (when (seq list)
       (if (> (count list) 1)
         [:select {:on {:change [:action/select-db]}
                   :value (or db-name "")
                   :aria-label "Select database"
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
            [:option {:key name :value name} name])]
         ;; Single database — show name as read-only label
         [:div {:style {:position "fixed"
                        :top "12px"
                        :right "16px"
                        :background "rgba(10,14,23,0.5)"
                        :border "1px solid rgba(255,255,255,0.06)"
                        :border-radius (:radius styles/tokens)
                        :color "rgba(255,255,255,0.4)"
                        :padding "4px 8px"
                        :font-size "11px"}}
          db-name]))
     ;; Past questions — bottom left, discreet
     [:div {:style {:position "fixed"
                    :bottom "24px"
                    :left "12px"
                    :max-width "360px"
                    :max-height "50vh"
                    :overflow-y "auto"
                    :z-index 10}}
      (ask/past-questions-panel state)]
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
