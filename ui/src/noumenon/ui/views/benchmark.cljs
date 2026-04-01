(ns noumenon.ui.views.benchmark
  (:require [noumenon.ui.styles :as styles]
            [noumenon.ui.components.button :as button]
            [noumenon.ui.components.card :as card]
            [noumenon.ui.components.progress :as progress]
            [noumenon.ui.components.badge :as badge]
            [noumenon.ui.components.skeleton :as skeleton]))

(defn- format-score [score]
  (when score
    (str (.toFixed (* 100 score) 1) "%")))

(defn- run-row [{:keys [bench.run/id bench.run/started-at bench.run/score
                        bench.run/layers bench.run/questions-asked]}
                selected-ids]
  (let [selected? (contains? selected-ids id)]
    [:tr {:key id
          :style {:border-bottom (str "1px solid " (:border styles/tokens))
                  :background (when selected? (str (:accent styles/tokens) "10"))
                  :cursor "pointer"}
          :on {:click [:action/bench-toggle-select id]}}
     [:td {:style {:padding "8px 12px" :font-family (:font-mono styles/tokens)
                   :font-size "12px"}}
      (subs (str id) 0 (min 12 (count (str id))))]
     [:td {:style {:padding "8px 12px" :font-size "12px"
                   :color (:text-secondary styles/tokens)}}
      (when started-at
        (.toLocaleString (js/Date. started-at)))]
     [:td {:style {:padding "8px 12px"}}
      (when score
        (badge/badge {:color (cond (> score 0.7) (:success styles/tokens)
                                   (> score 0.4) (:warning styles/tokens)
                                   :else (:danger styles/tokens))}
                     (format-score score)))]
     [:td {:style {:padding "8px 12px" :font-size "12px"}} (or layers "")]
     [:td {:style {:padding "8px 12px" :font-size "12px"}} (or questions-asked "")]]))

(defn- runs-table [runs selected-ids]
  (when (seq runs)
    [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px"}}
     [:thead
      [:tr {:style {:border-bottom (str "1px solid " (:border styles/tokens))}}
       (for [label ["ID" "Started" "Score" "Layers" "Questions"]]
         [:th {:key label
               :style {:text-align "left" :padding "8px 12px"
                       :color (:text-secondary styles/tokens) :font-weight 500
                       :font-size "12px" :text-transform "uppercase"}}
          label])]]
     [:tbody
      (for [run runs]
        (run-row run selected-ids))]]))

(defn- compare-panel [{:keys [compare-data]}]
  (when compare-data
    (card/card {:style {:margin-top "16px"}}
               (card/card-header "Comparison")
               [:div {:style {:font-family (:font-mono styles/tokens)
                              :font-size "13px"
                              :white-space "pre-wrap"}}
                (pr-str compare-data)])))

(defn benchmark-view [state]
  (let [{:keys [bench/runs bench/loading? bench/running?
                bench/progress bench/selected-ids bench/compare-data
                databases/list db-name]} state
        selected (or selected-ids #{})]
    [:div {:style {:max-width "900px"}}
     [:div {:style {:display "flex" :justify-content "space-between"
                    :align-items "center" :margin-bottom "20px"}}
      [:h2 {:style {:font-size "1.4rem" :font-weight 600}} "Benchmarks"]
      [:div {:style {:display "flex" :gap "8px"}}
       (when (= 2 (count selected))
         (button/secondary {:on {:click [:action/bench-compare]}} "Compare"))
       (button/primary {:on {:click [:action/bench-run]}
                        :disabled? running?}
                       (if running? "Running..." "Run Benchmark"))
       (button/secondary {:on {:click [:action/bench-refresh]}} "Refresh")]]
     (when running?
       [:div {:style {:margin-bottom "16px"}}
        (if progress
          (progress/bar progress)
          [:div {:style {:color (:text-muted styles/tokens) :font-size "13px"}}
           "Starting benchmark..."])])
     (cond
       loading?
       (skeleton/rows {:count 4 :height "36px"})

       (empty? runs)
       (card/card {}
                  [:p {:style {:color (:text-secondary styles/tokens)
                               :text-align "center" :padding "20px"}}
                   "No benchmark runs found. Run a benchmark to see results."])

       :else
       (runs-table runs selected))
     (compare-panel {:compare-data compare-data})]))
