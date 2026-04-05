(ns noumenon.ui.components.table
  (:require [noumenon.ui.styles :as styles]))

(defn data-table
  "Render a data table. columns is [{:key :name :label \"Name\"} ...].
   rows is a seq of maps."
  [{:keys [columns rows]}]
  [:table {:style {:width "100%"
                   :border-collapse "collapse"
                   :font-size "13px"}}
   [:thead
    [:tr {:style {:border-bottom (str "1px solid " (:border styles/tokens))}}
     (for [{:keys [key label]} columns]
       [:th {:key key
             :style {:text-align "left"
                     :padding "8px 12px"
                     :color (:text-secondary styles/tokens)
                     :font-weight 500
                     :font-size "12px"
                     :text-transform "uppercase"
                     :letter-spacing "0.5px"}}
        label])]]
   [:tbody
    (if (seq rows)
      (for [row rows]
        [:tr {:key (or (:name row) (str (hash row)))
              :style {:border-bottom (str "1px solid " (:border styles/tokens))}}
         (for [{:keys [key render]} columns]
           (let [text (if render (render row) (str (get row key)))]
             [:td {:key key
                   :title (when (string? text) text)
                   :style {:padding "8px 12px"
                           :max-width "300px"
                           :overflow "hidden"
                           :text-overflow "ellipsis"
                           :white-space "nowrap"}}
              text]))])
      [[:tr
        [:td {:col-span (count columns)
              :style {:padding "20px 12px"
                      :text-align "center"
                      :color (:text-muted styles/tokens)
                      :font-style "italic"}}
         "No results"]]])]])
