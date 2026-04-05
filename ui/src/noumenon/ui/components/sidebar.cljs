(ns noumenon.ui.components.sidebar
  (:require [noumenon.ui.styles :as styles]))

(def ^:private nav-items
  [{:route :ask        :label "Ask"        :icon "\u2728"}
   {:route :databases  :label "Databases"  :icon "\u2630"}
   {:route :graph      :label "Graph"      :icon "\u25C9"}
   {:route :schema     :label "Schema"     :icon "\u2261"}
   {:route :benchmark  :label "Benchmark"  :icon "\u25B6"}
   {:route :introspect :label "Introspect" :icon "\u2699"}
   {:route :history    :label "History"    :icon "\u23F3"}])

(defn- nav-item [{:keys [route label icon active? collapsed?]}]
  [:a {:key (name route)
       :href (str "#/" (name route))
       :title label
       :style {:display "flex"
               :align-items "center"
               :gap "10px"
               :padding (if collapsed? "8px 12px" "8px 16px")
               :border-radius (:radius styles/tokens)
               :color (if active?
                        (:text-primary styles/tokens)
                        (:text-secondary styles/tokens))
               :background (when active? (:bg-tertiary styles/tokens))
               :text-decoration "none"
               :font-size "13px"
               :font-weight (if active? 600 400)
               :justify-content (when collapsed? "center")}}
   (when collapsed?
     [:span {:style {:font-weight 600 :font-size "14px"}} icon])
   (when-not collapsed? label)])

(defn sidebar [{:keys [active-route collapsed? theme backends active-backend]}]
  [:nav {:style {:width (if collapsed? "48px" "200px")
                 :min-width (if collapsed? "48px" "200px")
                 :background (:bg-secondary styles/tokens)
                 :border-right (str "1px solid " (:border styles/tokens))
                 :padding (if collapsed? "8px 4px" "16px 8px")
                 :display "flex"
                 :flex-direction "column"
                 :gap "2px"
                 :transition "width 0.2s ease, min-width 0.2s ease"}}
   ;; Header / collapse toggle
   [:div {:style {:display "flex"
                  :align-items "center"
                  :justify-content (if collapsed? "center" "space-between")
                  :padding (if collapsed? "8px 0" "8px 16px")
                  :margin-bottom "16px"}}
    (when-not collapsed?
      [:span {:style {:font-size "16px"
                      :font-weight 700
                      :color (:accent styles/tokens)}}
       "Noumenon"])
    [:button {:on {:click [:action/sidebar-toggle]}
              :aria-label (if collapsed? "Expand sidebar" "Collapse sidebar")
              :style {:background "none"
                      :border "none"
                      :color (:text-muted styles/tokens)
                      :cursor "pointer"
                      :font-size "16px"
                      :padding "2px 4px"}}
     (if collapsed? "\u25B6" "\u25C0")]]
   (for [item nav-items]
     (nav-item (assoc item
                      :active? (= (:route item) active-route)
                      :collapsed? collapsed?)))
   ;; Spacer
   [:div {:style {:flex 1}}]
   ;; Backend switcher
   (when (and (not collapsed?) (> (count backends) 1))
     [:select {:on {:change [:action/backend-switch-input]}
               :value (or active-backend "")
               :title "Active backend"
               :aria-label "Active backend"
               :style {:margin "4px 8px"
                       :padding "4px 8px"
                       :background (:bg-tertiary styles/tokens)
                       :border (str "1px solid " (:border styles/tokens))
                       :border-radius (:radius styles/tokens)
                       :color (:text-secondary styles/tokens)
                       :font-size "11px"}}
      (for [{:keys [name]} backends]
        [:option {:key name :value name} name])])
   (when (and collapsed? (> (count backends) 1))
     [:div {:title (str "Backend: " active-backend)
            :style {:text-align "center"
                    :font-size "10px"
                    :color (:text-muted styles/tokens)
                    :padding "4px"}}
      (subs (or active-backend "L") 0 1)])
   ;; Theme toggle
   [:button {:on {:click [:action/theme-toggle]}
             :title (if (= theme :light) "Switch to dark" "Switch to light")
             :aria-label (if (= theme :light) "Switch to dark" "Switch to light")
             :style {:background "none"
                     :border "none"
                     :color (:text-muted styles/tokens)
                     :cursor "pointer"
                     :font-size "16px"
                     :padding "8px"
                     :text-align "center"}}
    (if (= theme :light) "\u263E" "\u2600")]])
