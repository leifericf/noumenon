(ns noumenon.ui.views.introspect
  (:require [noumenon.ui.styles :as styles]
            [noumenon.ui.components.button :as button]
            [noumenon.ui.components.card :as card]
            [noumenon.ui.components.progress :as progress]
            [noumenon.ui.components.badge :as badge]
            [noumenon.ui.components.skeleton :as skeleton]))

(defn- status-badge [status]
  (badge/badge {:color (case status
                         "running" (:warning styles/tokens)
                         "completed" (:success styles/tokens)
                         "stopped" (:text-muted styles/tokens)
                         (:text-secondary styles/tokens))}
               (or status "unknown")))

(defn introspect-view [state]
  (let [{:keys [introspect/status introspect/progress introspect/running?
                introspect/history introspect/loading?]} state]
    [:div {:style {:max-width "900px"}}
     [:div {:style {:display "flex" :justify-content "space-between"
                    :align-items "center" :margin-bottom "20px"}}
      [:h2 {:style {:font-size "1.4rem" :font-weight 600}} "Introspect"]
      [:div {:style {:display "flex" :gap "8px"}}
       (if running?
         (button/danger {:on {:click [:action/introspect-stop]}} "Stop")
         (button/primary {:on {:click [:action/introspect-start]}} "Start"))
       (button/secondary {:on {:click [:action/introspect-refresh]}} "Refresh")]]
     (when status
       (card/card {:style {:margin-bottom "16px"}}
                  [:div {:style {:display "flex" :align-items "center" :gap "12px"}}
                   [:span {:style {:font-size "13px" :color (:text-secondary styles/tokens)}}
                    "Status:"]
                   (status-badge (:status status))
                   (when (:run-id status)
                     [:span {:style {:font-family (:font-mono styles/tokens)
                                     :font-size "12px"
                                     :color (:text-muted styles/tokens)}}
                      (str "Run: " (subs (str (:run-id status)) 0 12))])]))
     (when (and running? progress)
       [:div {:style {:margin-bottom "16px"}}
        (progress/bar progress)])
     (cond
       loading?
       (skeleton/rows {:count 5 :height "32px"})

       (seq history)
       [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px"}}
        [:thead
         [:tr {:style {:border-bottom (str "1px solid " (:border styles/tokens))}}
          (for [label ["Iteration" "Target" "Action" "Score Delta" "Status"]]
            [:th {:key label
                  :style {:text-align "left" :padding "8px 12px"
                          :color (:text-secondary styles/tokens) :font-weight 500
                          :font-size "12px" :text-transform "uppercase"}}
             label])]]
        [:tbody
         (for [[i row] (map-indexed vector history)]
           [:tr {:key i :style {:border-bottom (str "1px solid " (:border styles/tokens))}}
            [:td {:style {:padding "8px 12px"}} (get row 0)]
            [:td {:style {:padding "8px 12px"}} (get row 1)]
            [:td {:style {:padding "8px 12px"}} (get row 2)]
            [:td {:style {:padding "8px 12px"}}
             (when-let [delta (get row 3)]
               (badge/badge {:color (if (pos? delta) (:success styles/tokens) (:danger styles/tokens))}
                            (str (when (pos? delta) "+") delta)))]
            [:td {:style {:padding "8px 12px"}} (get row 4)]])]]

       :else
       (card/card {}
                  [:p {:style {:color (:text-secondary styles/tokens)
                               :text-align "center" :padding "20px"}}
                   "No introspect history. Start an introspect session to begin."]))]))
