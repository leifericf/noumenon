(ns noumenon.ui.views.history
  (:require [noumenon.ui.styles :as styles]
            [noumenon.ui.components.button :as button]
            [noumenon.ui.components.card :as card]
            [noumenon.ui.components.badge :as badge]
            [noumenon.ui.components.skeleton :as skeleton]))

(defn- format-date [d]
  (when d
    (let [date (js/Date. d)]
      (.toLocaleString date "en-US" (clj->js {:month "short" :day "numeric"
                                              :hour "2-digit" :minute "2-digit"})))))

(defn- commit-row [commit]
  (let [[sha message author kind date added deleted] commit]
    [:tr {:key sha
          :style {:border-bottom (str "1px solid " (:border styles/tokens))}}
     [:td {:style {:padding "8px 12px" :font-family (:font-mono styles/tokens)
                   :font-size "12px" :color (:accent styles/tokens)}}
      (when sha (subs (str sha) 0 (min 7 (count (str sha)))))]
     [:td {:style {:padding "8px 12px" :font-size "13px"}}
      (when kind
        (badge/badge {:color (case (str kind)
                               "feat"     (:success styles/tokens)
                               "fix"      (:danger styles/tokens)
                               "refactor" (:warning styles/tokens)
                               (:text-muted styles/tokens))}
                     (str kind)))]
     [:td {:style {:padding "8px 12px" :font-size "13px"
                   :max-width "400px" :overflow "hidden"
                   :text-overflow "ellipsis" :white-space "nowrap"}}
      message]
     [:td {:style {:padding "8px 12px" :font-size "12px"
                   :color (:text-secondary styles/tokens)}}
      author]
     [:td {:style {:padding "8px 12px" :font-size "12px"
                   :color (:text-muted styles/tokens)}}
      (format-date date)]
     [:td {:style {:padding "8px 12px" :font-size "12px"}}
      (when (and added deleted)
        [:span
         [:span {:style {:color (:success styles/tokens)}} (str "+" added)]
         " "
         [:span {:style {:color (:danger styles/tokens)}} (str "-" deleted)]])]]))

(defn history-view [state]
  (let [{:keys [history/commits history/loading?]} state]
    [:div {:style {:max-width "1000px"}}
     [:div {:style {:display "flex" :justify-content "space-between"
                    :align-items "center" :margin-bottom "20px"}}
      [:h2 {:style {:font-size "1.4rem" :font-weight 600}} "History"]
      (button/secondary {:on {:click [:action/history-refresh]}} "Refresh")]
     (cond
       loading?
       (skeleton/rows {:count 8 :height "32px"})

       (empty? commits)
       (card/card {}
                  [:div {:style {:text-align "center" :padding "20px"}}
                   [:p {:style {:color (:text-secondary styles/tokens)}}
                    "No commit history found for this database."]
                   [:div {:style {:margin-top "10px"}}
                    (button/secondary {:on {:click [:action/history-refresh]}} "Refresh")]])

       :else
       [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px"}}
        [:thead
         [:tr {:style {:border-bottom (str "1px solid " (:border styles/tokens))}}
          (for [label ["SHA" "Type" "Message" "Author" "Date" "Changes"]]
            [:th {:key label
                  :style {:text-align "left" :padding "8px 12px"
                          :color (:text-secondary styles/tokens) :font-weight 500
                          :font-size "12px" :text-transform "uppercase"}}
             label])]]
        [:tbody
         (for [commit commits]
           (commit-row commit))]])]))
