(ns noumenon.ui.components.toast
  (:require [noumenon.ui.styles :as styles]))

(defn toast [{:keys [id message type]}]
  (let [color (case type
                :error   (:danger styles/tokens)
                :success (:success styles/tokens)
                :warning (:warning styles/tokens)
                (:text-primary styles/tokens))]
    [:div {:style {:padding "12px 20px"
                   :background (:bg-secondary styles/tokens)
                   :border (str "1px solid " color)
                   :border-radius (:radius styles/tokens)
                   :color color
                   :font-size "13px"
                   :display "flex"
                   :align-items "center"
                   :gap "12px"}}
     [:span {:style {:flex 1}} message]
     [:button {:on {:click [:action/toast-dismiss id]}
               :style {:background "none"
                       :border "none"
                       :color (:text-muted styles/tokens)
                       :cursor "pointer"
                       :font-size "16px"
                       :padding "0 4px"
                       :line-height 1}}
      "\u00D7"]]))

(defn toast-container [toasts]
  (when (seq toasts)
    [:div {:style {:position "fixed"
                   :bottom "20px"
                   :right "20px"
                   :display "flex"
                   :flex-direction "column"
                   :gap "8px"
                   :z-index 1000
                   :max-width "400px"}}
     (for [{:keys [id] :as t} toasts]
       [:div {:key id} (toast t)])]))
