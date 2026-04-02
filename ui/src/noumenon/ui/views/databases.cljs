(ns noumenon.ui.views.databases
  (:require [noumenon.ui.styles :as styles]
            [noumenon.ui.components.card :as card]
            [noumenon.ui.components.button :as button]
            [noumenon.ui.components.progress :as progress]
            [noumenon.ui.components.badge :as badge]
            [noumenon.ui.components.table :as table]
            [noumenon.ui.components.skeleton :as skeleton]))

(defn- format-date [d]
  (when d
    (let [date (js/Date. d)]
      (.toLocaleDateString date "en-US" (clj->js {:month "short" :day "numeric" :year "numeric"})))))

(defn- format-cost [c]
  (when (and c (pos? c))
    (str "$" (.toFixed c 4))))

(defn- op-buttons [db-name operation]
  [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap"}}
   (when-not operation
     (for [[op label] [[:import "Import"] [:enrich "Enrich"]
                       [:analyze "Analyze"] [:digest "Digest"]]]
       [:span {:key (name op)}
        (button/small {:on {:click [:action/db-operation db-name op]}} label)]))
   (when-not operation
     (button/small {:on {:click [:action/db-delete db-name]}
                    :color (:danger styles/tokens)}
                   "Delete"))])

(defn- db-row [db operation]
  (let [{:keys [name commits files dirs latest cost]} db]
    [:tr {:key name
          :style {:border-bottom (str "1px solid " (:border styles/tokens))}}
     [:td {:style {:padding "10px 12px" :font-weight 500}} name]
     [:td {:style {:padding "10px 12px"}} commits]
     [:td {:style {:padding "10px 12px"}} files]
     [:td {:style {:padding "10px 12px"}} dirs]
     [:td {:style {:padding "10px 12px" :color (:text-secondary styles/tokens)
                   :font-size "12px"}}
      (format-date latest)]
     [:td {:style {:padding "10px 12px" :font-family (:font-mono styles/tokens)
                   :font-size "12px"}}
      (format-cost cost)]
     [:td {:style {:padding "10px 12px"}}
      (if operation
        [:div {:style {:min-width "200px"}}
         (badge/badge {:color (:warning styles/tokens)}
                      (str (name (:op operation)) "..."))
         (when (:progress operation)
           [:div {:style {:margin-top "6px"}}
            (progress/bar (:progress operation))])]
        (op-buttons name nil))]]))

(def ^:private columns
  [{:label "Name"} {:label "Commits"} {:label "Files"} {:label "Dirs"}
   {:label "Latest"} {:label "Cost"} {:label "Actions"}])

(defn databases-view [state]
  (let [{:keys [databases/list databases/operations databases/loading?
                databases/error]} state]
    [:div
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :align-items "center"
                    :margin-bottom "20px"}}
      [:h2 {:style {:font-size "1.4rem" :font-weight 600}} "Databases"]
      (button/primary {:on {:click [:action/db-refresh]}}
                      "Refresh")]
     ;; Add repo
     [:div {:style {:display "flex" :gap "8px" :margin-bottom "20px"}}
      [:input {:type "text"
               :placeholder "Path to git repository..."
               :value (or (:databases/new-repo state) "")
               :on {:input [:action/db-new-repo-input]
                    :keydown [:action/db-new-repo-keydown]}
               :style {:flex 1
                       :padding "10px 14px"
                       :background (:bg-secondary styles/tokens)
                       :border (str "1px solid " (:border styles/tokens))
                       :border-radius (:radius styles/tokens)
                       :color (:text-primary styles/tokens)
                       :font-size "14px"}}]
      (button/primary {:on {:click [:action/db-import-new]}} "Import")]
     (when error
       (card/card {:style {:background "#f8514920" :border-color (:danger styles/tokens)
                           :margin-bottom "16px"}}
                  [:span {:style {:color (:danger styles/tokens)}} error]))
     (cond
       loading?
       [:div {:style {:padding "20px"}}
        (skeleton/rows {:count 5 :height "40px" :gap "12px"})]

       (and (empty? list) (= :error (:daemon/status state)))
       (card/card {:style {:border-color (:warning styles/tokens)}}
                  [:div {:style {:text-align "center" :padding "20px"}}
                   [:p {:style {:color (:text-primary styles/tokens) :font-size "15px"
                                :margin-bottom "8px"}}
                    "Cannot connect to daemon"]
                   [:p {:style {:color (:text-secondary styles/tokens) :font-size "13px"}}
                    "Start the daemon with "
                    [:code {:style {:background (:bg-tertiary styles/tokens)
                                    :padding "2px 6px" :border-radius "3px"}}
                     "noum start"]
                    " or open via "
                    [:code {:style {:background (:bg-tertiary styles/tokens)
                                    :padding "2px 6px" :border-radius "3px"}}
                     "noum open"]]
                   [:div {:style {:margin-top "12px"}}
                    (button/primary {:on {:click [:action/db-refresh]}} "Retry")]])

       (empty? list)
       (card/card {}
                  [:p {:style {:color (:text-secondary styles/tokens)
                               :text-align "center" :padding "20px"}}
                   "No databases found. Import a repository to get started."])

       :else
       [:table {:style {:width "100%" :border-collapse "collapse" :font-size "13px"}}
        [:thead
         [:tr {:style {:border-bottom (str "1px solid " (:border styles/tokens))}}
          (for [{:keys [label]} columns]
            [:th {:key label
                  :style {:text-align "left" :padding "8px 12px"
                          :color (:text-secondary styles/tokens) :font-weight 500
                          :font-size "12px" :text-transform "uppercase"
                          :letter-spacing "0.5px"}}
             label])]]
        [:tbody
         (for [db list]
           (db-row db (get operations (:name db))))]])]))
