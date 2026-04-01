(ns noumenon.ui.components.card
  (:require [noumenon.ui.styles :as styles]))

(defn card [{:keys [style]} & children]
  (into [:div {:style (merge {:background (:bg-secondary styles/tokens)
                              :border (str "1px solid " (:border styles/tokens))
                              :border-radius (:radius styles/tokens)
                              :padding "16px"}
                             style)}]
        children))

(defn card-header [title & children]
  (into [:div {:style {:display "flex"
                       :align-items "center"
                       :justify-content "space-between"
                       :margin-bottom "12px"}}
         [:h3 {:style {:font-size "15px"
                       :font-weight 600
                       :margin 0}}
          title]]
        children))
