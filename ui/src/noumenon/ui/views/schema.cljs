(ns noumenon.ui.views.schema
  (:require [clojure.string :as str]
            [noumenon.ui.styles :as styles]
            [noumenon.ui.components.button :as button]
            [noumenon.ui.components.card :as card]
            [noumenon.ui.components.badge :as badge]))

;; --- Schema attributes panel ---

(defn- attr-item [{:keys [ident value-type cardinality unique?]} selected?]
  [:div {:key (str ident)
         :tabindex 0
         :role "option"
         :aria-selected selected?
         :on {:click [:action/schema-select-attr ident]
              :keydown [:action/schema-select-attr-key ident]}
         :style {:padding "6px 12px"
                 :cursor "pointer"
                 :border-radius (:radius styles/tokens)
                 :background (when selected? (:bg-tertiary styles/tokens))
                 :font-size "13px"
                 :display "flex"
                 :align-items "center"
                 :gap "8px"}}
   [:span {:style {:color (:accent styles/tokens)
                   :font-family (:font-mono styles/tokens)}}
    (str ident)]
   [:span {:style {:color (:text-muted styles/tokens)
                   :font-size "11px"}}
    (some-> value-type name)]
   (when (= cardinality :db.cardinality/many)
     (badge/badge {:color (:warning styles/tokens)} "many"))
   (when unique?
     (badge/badge {:color (:success styles/tokens)} "unique"))])

(defn- attrs-panel [{:keys [attrs selected-attr filter-text]}]
  [:div {:style {:min-width "280px"
                 :max-width "320px"
                 :border-right (str "1px solid " (:border styles/tokens))
                 :overflow-y "auto"
                 :padding "12px"}}
   [:input {:type "text"
            :placeholder "Filter attributes..."
            :value (or filter-text "")
            :on {:input [:action/schema-filter]}
            :style {:width "100%"
                    :padding "8px 12px"
                    :background (:bg-secondary styles/tokens)
                    :border (str "1px solid " (:border styles/tokens))
                    :border-radius (:radius styles/tokens)
                    :color (:text-primary styles/tokens)
                    :font-size "13px"
                    :margin-bottom "12px"}}]
   (let [filtered (->> attrs
                       (filter #(or (not (seq filter-text))
                                    (str/includes? (str (:ident %)) filter-text)))
                       (group-by #(namespace (:ident %)))
                       (sort-by first))]
     [:div {:role "listbox" :aria-label "Schema attributes"}
      (for [[ns-name ns-attrs] filtered]
        [:div {:key ns-name :style {:margin-bottom "12px"}}
         [:div {:style {:font-size "11px"
                        :font-weight 600
                        :color (:text-muted styles/tokens)
                        :text-transform "uppercase"
                        :padding "4px 12px"
                        :letter-spacing "0.5px"}}
          (or ns-name "db")]
         (for [attr (sort-by :ident ns-attrs)]
           (attr-item attr (= (:ident attr) selected-attr)))])])])

;; --- Named queries panel ---

(defn- query-item [{:keys [name description inputs]} selected?]
  [:div {:key name
         :tabindex 0
         :role "option"
         :aria-selected selected?
         :on {:click [:action/schema-select-query name]
              :keydown [:action/schema-select-query-key name]}
         :style {:padding "8px 12px"
                 :cursor "pointer"
                 :border-radius (:radius styles/tokens)
                 :background (when selected? (:bg-tertiary styles/tokens))
                 :margin-bottom "2px"}}
   [:div {:style {:font-size "13px" :font-weight 500}} name]
   (when description
     [:div {:style {:font-size "12px"
                    :color (:text-secondary styles/tokens)
                    :margin-top "2px"}}
      description])
   (when (seq inputs)
     [:div {:style {:display "flex" :gap "4px" :margin-top "4px"}}
      (for [input inputs]
        (badge/badge {} input))])])

(defn- queries-panel [{:keys [queries selected-query]}]
  [:div {:style {:min-width "280px"
                 :max-width "320px"
                 :border-right (str "1px solid " (:border styles/tokens))
                 :overflow-y "auto"
                 :padding "12px"}}
   [:div {:style {:font-size "12px"
                  :font-weight 600
                  :text-transform "uppercase"
                  :color (:text-muted styles/tokens)
                  :letter-spacing "0.5px"
                  :padding "4px 12px"
                  :margin-bottom "8px"}}
    "Named Queries"]
   [:div {:role "listbox" :aria-label "Named queries"}
    (for [q queries]
      (query-item q (= (:name q) selected-query)))]])

;; --- Results panel ---

(defn- results-table [results]
  (when (seq results)
    (let [first-row (first results)
          columns (if (map? first-row)
                    (keys first-row)
                    (range (count first-row)))]
      [:div {:style {:overflow-x "auto"}}
       [:table {:style {:width "100%"
                        :border-collapse "collapse"
                        :font-size "13px"
                        :font-family (:font-mono styles/tokens)}}
        [:thead
         [:tr {:style {:border-bottom (str "1px solid " (:border styles/tokens))}}
          (for [col columns]
            [:th {:key (str col)
                  :style {:text-align "left"
                          :padding "6px 10px"
                          :color (:text-secondary styles/tokens)
                          :font-size "11px"
                          :font-weight 500}}
             (str col)])]]
        [:tbody
         (for [[i row] (map-indexed vector results)]
           [:tr {:key i
                 :style {:border-bottom (str "1px solid " (:border styles/tokens))}}
            (for [[j col] (map-indexed vector columns)]
              [:td {:key j :style {:padding "6px 10px"
                                   :max-width "400px"
                                   :overflow "hidden"
                                   :text-overflow "ellipsis"
                                   :white-space "nowrap"}}
               (str (if (map? row) (get row col) (nth row j nil)))])])]]])))

;; --- Query workbench ---

(defn- query-workbench [{:keys [editor loading? results error history]}]
  [:div {:style {:padding "16px"}}
   [:div {:style {:display "flex"
                  :align-items "center"
                  :gap "12px"
                  :margin-bottom "12px"}}
    [:h3 {:style {:font-size "15px" :font-weight 600 :margin 0}}
     "Query Workbench"]
    (button/primary {:on {:click [:action/query-run]}
                     :disabled? loading?}
                    (if loading? "Running..." "Run Query"))
    (when (seq history)
      [:select {:on {:change [:action/query-history-select-input]}
                :value ""
                :style {:padding "4px 8px"
                        :background (:bg-secondary styles/tokens)
                        :border (str "1px solid " (:border styles/tokens))
                        :border-radius (:radius styles/tokens)
                        :color (:text-secondary styles/tokens)
                        :font-size "12px"}}
       [:option {:value ""} "History..."]
       (for [q (reverse history)]
         [:option {:key q :value q}
          (subs (str q) 0 (min 60 (count (str q))))])])]
   [:textarea {:value (or editor "")
               :on {:input [:action/query-editor-input]}
               :placeholder "[:find ?path\n :where [?f :file/path ?path]]"
               :rows 6
               :style {:width "100%"
                       :padding "12px 16px"
                       :background (:bg-secondary styles/tokens)
                       :border (str "1px solid " (:border styles/tokens))
                       :border-radius (:radius styles/tokens)
                       :color (:text-primary styles/tokens)
                       :font-family (:font-mono styles/tokens)
                       :font-size "13px"
                       :line-height "1.5"
                       :resize "vertical"}}]
   (when error
     (card/card {:style {:background "#f8514920"
                         :border-color (:danger styles/tokens)
                         :margin-top "12px"}}
                [:span {:style {:color (:danger styles/tokens)
                                :font-family (:font-mono styles/tokens)
                                :font-size "13px"}}
                 error]))
   (when results
     [:div {:style {:margin-top "12px"}}
      [:div {:style {:font-size "12px"
                     :color (:text-secondary styles/tokens)
                     :margin-bottom "8px"}}
       (str (:total results) " results")]
      (results-table (:results results))])])

;; --- Named query runner ---

(defn- named-query-runner [{:keys [selected-query queries query-params
                                   query-results query-loading?]}]
  (when selected-query
    (let [q (->> queries (filter #(= (:name %) selected-query)) first)]
      [:div {:style {:padding "16px"}}
       [:h3 {:style {:font-size "15px" :font-weight 600 :margin-bottom "12px"}}
        (:name q)]
       (when (:description q)
         [:p {:style {:color (:text-secondary styles/tokens)
                      :margin-bottom "12px"
                      :font-size "13px"}}
          (:description q)])
       (when (seq (:inputs q))
         [:div {:style {:display "flex" :gap "8px" :margin-bottom "12px"
                        :flex-wrap "wrap"}}
          (for [input (:inputs q)]
            [:div {:key input :style {:display "flex" :align-items "center" :gap "6px"}}
             [:label {:style {:font-size "12px" :color (:text-secondary styles/tokens)}}
              input]
             [:input {:type "text"
                      :value (get query-params input "")
                      :on {:input [:action/query-param-input input]}
                      :style {:padding "6px 10px"
                              :background (:bg-secondary styles/tokens)
                              :border (str "1px solid " (:border styles/tokens))
                              :border-radius (:radius styles/tokens)
                              :color (:text-primary styles/tokens)
                              :font-size "13px"
                              :width "200px"}}]])])
       (button/primary {:on {:click [:action/named-query-run selected-query]}
                        :disabled? query-loading?}
                       (if query-loading? "Running..." "Run"))
       (when query-results
         [:div {:style {:margin-top "12px"}}
          [:div {:style {:font-size "12px"
                         :color (:text-secondary styles/tokens)
                         :margin-bottom "8px"}}
           (str (:total query-results) " results")]
          (results-table (:results query-results))])])))

;; --- Main view ---

(defn- tab-button [label active? on-click-action]
  [:button {:on {:click on-click-action}
            :style {:padding "8px 16px"
                    :background (if active? (:bg-tertiary styles/tokens) "transparent")
                    :color (if active? (:text-primary styles/tokens) (:text-secondary styles/tokens))
                    :border "none"
                    :border-bottom (when active?
                                     (str "2px solid " (:accent styles/tokens)))
                    :font-size "13px"
                    :font-weight (if active? 600 400)
                    :cursor "pointer"}}
   label])

(defn schema-view [state]
  (let [{:keys [schema/tab schema/attrs schema/selected-attr schema/filter-text
                schema/queries schema/selected-query schema/query-params
                schema/query-results schema/query-loading?
                query/editor query/results query/loading? query/error
                databases/list db-name]} state
        active-tab (or tab :queries)]
    [:div {:style {:display "flex" :height "100%"}}
     ;; Left panel: tabs for attrs/queries
     [:div {:style {:display "flex" :flex-direction "column"
                    :min-width "280px" :max-width "320px"
                    :border-right (str "1px solid " (:border styles/tokens))}}
      [:div {:style {:display "flex"
                     :border-bottom (str "1px solid " (:border styles/tokens))}}
       (tab-button "Queries" (= active-tab :queries) [:action/schema-tab :queries])
       (tab-button "Schema" (= active-tab :schema) [:action/schema-tab :schema])
       (tab-button "Workbench" (= active-tab :workbench) [:action/schema-tab :workbench])]
      (case active-tab
        :schema  (attrs-panel {:attrs attrs :selected-attr selected-attr
                               :filter-text filter-text})
        :queries (queries-panel {:queries queries :selected-query selected-query})
        :workbench nil)]
     ;; Right panel: details/results
     [:div {:style {:flex 1 :overflow-y "auto"}}
      (case active-tab
        :workbench
        (query-workbench {:editor editor :loading? loading?
                          :results results :error error
                          :history (:query/history state)})

        :queries
        (if selected-query
          (named-query-runner {:selected-query selected-query :queries queries
                               :query-params query-params
                               :query-results query-results
                               :query-loading? query-loading?})
          [:div {:style {:padding "40px" :text-align "center"
                         :color (:text-muted styles/tokens) :font-size "14px"}}
           "Select a query from the left panel"])

        :schema
        (if selected-attr
          [:div {:style {:padding "16px"}}
           [:h3 {:style {:font-size "15px" :font-weight 600
                         :font-family (:font-mono styles/tokens)}}
            (str selected-attr)]
           (let [attr (->> attrs (filter #(= (:ident %) selected-attr)) first)]
             [:div {:style {:margin-top "12px"}}
              (for [[k v] (dissoc attr :ident)]
                [:div {:key (str k)
                       :style {:display "flex" :gap "12px" :margin-bottom "4px"
                               :font-size "13px"}}
                 [:span {:style {:color (:text-secondary styles/tokens) :min-width "100px"}}
                  (name k)]
                 [:span (str v)]])])]
          [:div {:style {:padding "40px" :text-align "center"
                         :color (:text-muted styles/tokens) :font-size "14px"}}
           "Select an attribute from the left panel"]))]]))
