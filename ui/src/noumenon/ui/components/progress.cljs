(ns noumenon.ui.components.progress
  (:require [noumenon.ui.styles :as styles]))

(defn bar
  "Progress bar. progress is {:current N :total M :message \"...\"}."
  [{:keys [current total message]}]
  (let [pct (if (and total (pos? total))
              (min 100 (* 100 (/ current total)))
              0)]
    [:div {:style {:width "100%"}}
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :margin-bottom "4px"
                    :font-size "12px"
                    :color (:text-secondary styles/tokens)}}
      [:span (or message "")]
      (when (and current total)
        [:span (str current "/" total)])]
     [:div {:style {:height "6px"
                    :background (:bg-tertiary styles/tokens)
                    :border-radius "3px"
                    :overflow "hidden"}}
      [:div {:style {:height "100%"
                     :width (str pct "%")
                     :background (:accent styles/tokens)
                     :border-radius "3px"
                     :transition "width 0.3s ease"}}]]]))
