(ns noumenon.ui.components.input
  (:require [noumenon.ui.styles :as styles]))

(def ^:private input-style
  {:padding "12px 16px"
   :background (:bg-secondary styles/tokens)
   :border (str "1px solid " (:border styles/tokens))
   :border-radius (:radius styles/tokens)
   :color (:text-primary styles/tokens)
   :font-size "15px"
   :width "100%"})

(defn text-input [{:keys [placeholder value on style]}]
  [:input {:type "text"
           :placeholder placeholder
           :value (or value "")
           :on on
           :style (merge input-style style)}])

(defn search-bar [{:keys [placeholder value on-input on-submit loading?]}]
  [:div {:style {:display "flex" :width "100%" :gap "8px"}}
   [:input {:type "text"
            :placeholder (or placeholder "Search...")
            :value (or value "")
            :on {:input on-input
                 :keydown on-submit}
            :style (merge input-style {:flex 1})}]
   [:button {:on {:click on-submit}
             :disabled loading?
             :style {:padding "12px 20px"
                     :background (:accent styles/tokens)
                     :color "#fff"
                     :border "none"
                     :border-radius (:radius styles/tokens)
                     :font-size "15px"
                     :cursor "pointer"
                     :white-space "nowrap"}}
    (if loading? "..." "Ask")]])

(defn textarea [{:keys [placeholder value on style rows]}]
  [:textarea {:placeholder placeholder
              :value (or value "")
              :on on
              :rows (or rows 6)
              :style (merge input-style
                            {:resize "vertical"
                             :font-family (:font-mono styles/tokens)
                             :font-size "13px"
                             :line-height "1.5"}
                            style)}])
