(ns noumenon.ui.components.skeleton
  (:require [noumenon.ui.styles :as styles]))

(defn bar
  "A pulsing skeleton loading bar."
  [{:keys [width height]}]
  [:div {:style {:width (or width "100%")
                 :height (or height "16px")
                 :background (:bg-tertiary styles/tokens)
                 :border-radius (:radius styles/tokens)
                 :animation "pulse 1.5s ease-in-out infinite"}}])

(defn rows
  "Multiple skeleton bars stacked."
  [{:keys [count width height gap]}]
  [:div {:style {:display "flex" :flex-direction "column"
                 :gap (or gap "8px")}}
   (for [i (range (or count 3))]
     [:div {:key i}
      (bar {:width (or width (str (- 100 (* i 10)) "%"))
            :height height})])])

(defn inject-keyframes! []
  (let [el (or (.getElementById js/document "noumenon-keyframes")
               (let [s (.createElement js/document "style")]
                 (set! (.-id s) "noumenon-keyframes")
                 (.appendChild (.-head js/document) s)
                 s))]
    (set! (.-textContent el)
          (str "@keyframes pulse { 0%, 100% { opacity: 0.4; } 50% { opacity: 0.8; } }\n"
               "@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }\n"
               "@keyframes fadeSlideIn { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; transform: translateY(0); } }\n"
               "@keyframes sparkleIn { 0% { opacity: 0; transform: scale(0.95); filter: blur(2px); } 50% { opacity: 0.7; filter: blur(0); } 100% { opacity: 1; transform: scale(1); } }\n"
               "@keyframes tickerScroll { 0% { transform: translateX(0); } 100% { transform: translateX(-50%); } }\n"
               ".ticker-paused { animation-play-state: paused !important; }"))))
