(ns noumenon.ui.components.badge
  (:require [noumenon.ui.styles :as styles]))

(defn badge [{:keys [color]} label]
  [:span {:style {:display "inline-block"
                  :padding "2px 8px"
                  :border-radius "12px"
                  :font-size "11px"
                  :font-weight 500
                  :background (str (or color (:accent styles/tokens)) "20")
                  :color (or color (:accent styles/tokens))}}
   label])
