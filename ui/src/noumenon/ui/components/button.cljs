(ns noumenon.ui.components.button
  (:require [noumenon.ui.styles :as styles]))

(def ^:private base-style
  {:border "none"
   :border-radius (:radius styles/tokens)
   :padding "6px 14px"
   :font-size "13px"
   :font-weight 500
   :cursor "pointer"
   :transition "background 0.15s, opacity 0.15s"})

(defn primary [{:keys [on disabled?]} & children]
  (into [:button (cond-> {:style (merge base-style
                                        {:background (:accent styles/tokens)
                                         :color "#fff"})
                          :on on}
                   disabled? (assoc :disabled true
                                    :style (merge base-style
                                                  {:background (:text-muted styles/tokens)
                                                   :color (:text-secondary styles/tokens)
                                                   :cursor "not-allowed"})))]
        children))

(defn secondary [{:keys [on disabled?]} & children]
  (into [:button (cond-> {:style (merge base-style
                                        {:background "transparent"
                                         :color (:text-primary styles/tokens)
                                         :border (str "1px solid " (:border styles/tokens))})
                          :on on}
                   disabled? (assoc :disabled true
                                    :style (merge base-style
                                                  {:background "transparent"
                                                   :color (:text-muted styles/tokens)
                                                   :border (str "1px solid " (:text-muted styles/tokens))
                                                   :cursor "not-allowed"})))]
        children))

(defn danger [{:keys [on]} & children]
  (into [:button {:style (merge base-style
                                {:background "transparent"
                                 :color (:danger styles/tokens)
                                 :border (str "1px solid " (:danger styles/tokens))})
                  :on on}]
        children))

(defn small [{:keys [on color]} & children]
  (into [:button {:style (merge base-style
                                {:padding "3px 10px"
                                 :font-size "12px"
                                 :background "transparent"
                                 :color (or color (:accent styles/tokens))
                                 :border (str "1px solid " (or color (:accent styles/tokens)))})
                  :on on}]
        children))
