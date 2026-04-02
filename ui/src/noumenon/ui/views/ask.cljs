(ns noumenon.ui.views.ask
  (:require [noumenon.ui.styles :as styles]
            [noumenon.ui.components.markdown :as md]))

(defn- db-selector [{:keys [databases selected]}]
  (when (and (seq databases) (> (count databases) 1))
    [:select {:on {:change [:action/select-db]}
              :value (or selected "")
              :style {:background "transparent"
                      :border "none"
                      :color (:text-muted styles/tokens)
                      :padding "4px 2px"
                      :font-size "12px"
                      :cursor "pointer"
                      :text-align "right"}}
     (for [{:keys [name]} databases]
       [:option {:key name :value name} name])]))

(defn- format-elapsed [ms]
  (when (and ms (pos? ms))
    (if (< ms 1000)
      (str ms "ms")
      (str (.toFixed (/ ms 1000.0) 1) "s"))))

;; --- Reasoning trace components ---

(defn- step-icon [step-type]
  (let [color (case step-type
                "thinking" (:accent styles/tokens)
                "step"     (:success styles/tokens)
                "done"     (:accent styles/tokens)
                (:text-muted styles/tokens))]
    [:div {:style {:width "8px" :height "8px" :border-radius "50%"
                   :background color
                   :margin-top "5px"
                   :flex-shrink 0}}]))

(defn- sample-pills [samples]
  (when (seq samples)
    [:div {:style {:display "flex" :gap "4px" :flex-wrap "wrap" :margin-top "4px"}}
     (for [[i s] (map-indexed vector samples)]
       [:span {:key i
               :style {:display "inline-block"
                       :padding "1px 8px"
                       :background (str (:accent styles/tokens) "15")
                       :border-radius "10px"
                       :font-size "11px"
                       :color (:text-secondary styles/tokens)
                       :max-width "250px"
                       :overflow "hidden"
                       :text-overflow "ellipsis"
                       :white-space "nowrap"}}
        s])]))

(defn- reasoning-step [{:keys [type message reasoning results samples elapsed]} idx total]
  [:div {:key idx
         :style {:display "flex"
                 :gap "10px"
                 :padding "8px 0"
                 :animation "fadeSlideIn 0.3s ease"
                 :border-bottom (when (< idx (dec total))
                                  (str "1px solid " (str (:border styles/tokens) "80")))}}
   ;; Timeline dot
   (step-icon type)
   ;; Content
   [:div {:style {:flex 1 :min-width 0}}
    ;; Main message
    [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
     [:span {:style {:font-size "13px"
                     :color (if (= type "thinking")
                              (:text-muted styles/tokens)
                              (:text-primary styles/tokens))
                     :font-style (when (= type "thinking") "italic")}}
      message]
     (when-let [t (format-elapsed elapsed)]
       [:span {:style {:font-size "11px"
                       :color (:text-muted styles/tokens)
                       :padding "1px 6px"
                       :background (:bg-tertiary styles/tokens)
                       :border-radius "8px"}}
        t])
     (when (and results (pos? results) (= type "step"))
       [:span {:style {:font-size "11px"
                       :color (:success styles/tokens)
                       :padding "1px 6px"
                       :background (str (:success styles/tokens) "15")
                       :border-radius "8px"}}
        (case results
          1 "Found 1 result"
          (str "Found " results " results"))])
     (when (and (seq samples) (= type "step"))
       [:button {:on {:click [:action/ask-show-step-on-graph samples]}
                 :style {:background (str (:accent styles/tokens) "15")
                         :border "none" :cursor "pointer"
                         :border-radius "8px"
                         :font-size "11px" :color (:accent styles/tokens)
                         :padding "1px 6px"}}
        "graph"])
     (when (and results (> results 5) (seq samples) (= type "step"))
       [:button {:on {:click [:action/ask-download-csv message samples]}
                 :style {:background (str (:text-muted styles/tokens) "15")
                         :border "none" :cursor "pointer"
                         :border-radius "8px"
                         :font-size "11px" :color (:text-secondary styles/tokens)
                         :padding "1px 6px"}}
        "\u2193 csv"])]
    ;; LLM reasoning (italic, lighter)
    (when (and reasoning (= type "step"))
      [:div {:style {:font-size "12px"
                     :color (:text-muted styles/tokens)
                     :font-style "italic"
                     :margin-top "3px"
                     :line-height "1.4"}}
       reasoning])
    ;; Sample data pills
    (when (= type "step")
      (sample-pills samples))]])

(defn- active-spinner [message]
  [:div {:style {:display "flex" :align-items "center" :gap "10px"
                 :padding "10px 0"}}
   [:div {:style {:width "14px" :height "14px" :border-radius "50%"
                  :border (str "2px solid " (:accent styles/tokens))
                  :border-top-color "transparent"
                  :animation "spin 0.8s linear infinite"
                  :flex-shrink 0}}]
   [:span {:style {:font-size "13px"
                   :color (:text-secondary styles/tokens)
                   :font-style "italic"}}
    (or message "Connecting...")]])

(defn- reasoning-trace [steps progress expanded?]
  (let [real-steps (filterv #(not= "thinking" (:type %)) steps)
        n          (count real-steps)
        show-all?  (or expanded? (<= n 3))
        visible    (if show-all?
                     (map-indexed vector real-steps)
                     (map-indexed vector (take-last 3 real-steps)))]
    [:div {:style {:width "100%"
                   :margin-top "20px"
                   :padding "16px 20px"
                   :background "rgba(15,20,30,0.75)"
                   :border (str "1px solid " (:border styles/tokens))
                   :border-radius (:radius styles/tokens)}}
     ;; Header with expand/collapse
     [:div {:style {:display "flex" :align-items "center" :gap "8px"
                    :margin-bottom "8px"
                    :padding-bottom "8px"
                    :border-bottom (str "1px solid " (:border styles/tokens))}}
      [:span {:style {:font-size "12px" :font-weight 600
                      :text-transform "uppercase" :letter-spacing "0.5px"
                      :color (:text-muted styles/tokens)}}
       "Agent steps"]
      (when (> n 3)
        [:button {:on {:click [:action/ask-toggle-reasoning]}
                  :aria-expanded (boolean show-all?)
                  :style {:background "none" :border "none" :cursor "pointer"
                          :font-size "11px" :color (:accent styles/tokens)
                          :padding "0"}}
         (if show-all? "collapse" (str "show all " n " steps"))])]
     ;; Steps container — newest on top, oldest fades at bottom
     [:div {:style (if show-all?
                     {}
                     {:max-height "140px"
                      :overflow "hidden"
                      :position "relative"
                      :-webkit-mask-image "linear-gradient(to bottom, black 0%, black 70%, transparent 100%)"
                      :mask-image "linear-gradient(to bottom, black 0%, black 70%, transparent 100%)"})}
      (for [[i step] (reverse visible)]
        (reasoning-step step i n))]
     ;; Active spinner
     (when progress
       (active-spinner (:message progress)))]))

;; --- History item ---

(defn- history-item [{:keys [question answer]}]
  [:div {:style {:margin-bottom "20px"
                 :padding-bottom "20px"
                 :border-bottom (str "1px solid " (:border styles/tokens))}}
   [:div {:style {:font-weight 600
                  :color (:accent styles/tokens)
                  :margin-bottom "8px"}}
    question]
   [:div {:style {:background "rgba(15,20,30,0.75)"
                  :border (str "1px solid " (:border styles/tokens))
                  :border-radius (:radius styles/tokens)
                  :padding "16px"
                  :position "relative"}}
    [:button {:on {:click [:action/copy-to-clipboard answer]}
              :style {:position "absolute" :top "10px" :right "10px"
                      :background "rgba(255,255,255,0.05)"
                      :border "1px solid rgba(255,255,255,0.08)"
                      :border-radius "6px"
                      :cursor "pointer"
                      :color "rgba(255,255,255,0.4)"
                      :font-size "11px"
                      :padding "4px 10px"
                      :letter-spacing "0.3px"}
              :title "Copy to clipboard"}
     "Copy"]
    (md/render-markdown answer)]])

;; --- Main view ---

(defn- format-ago [date-str]
  (when date-str
    (let [then (.getTime (js/Date. date-str))
          now  (.getTime (js/Date.))
          diff (/ (- now then) 1000)]
      (cond
        (< diff 60)    "just now"
        (< diff 3600)  (str (int (/ diff 60)) "m ago")
        (< diff 86400) (str (int (/ diff 3600)) "h ago")
        :else          (str (int (/ diff 86400)) "d ago")))))

(defn- status-badge [status]
  (let [s (if (keyword? status) (name status) (str status))]
    (when (not= s "answered") ;; Don't show badge for normal answered sessions
      [:span {:style {:font-size "11px"
                      :padding "2px 8px"
                      :border-radius "10px"
                      :white-space "nowrap"
                      :background (case s
                                    "budget-exhausted" (str (:warning styles/tokens) "15")
                                    "error" (str (:danger styles/tokens) "15")
                                    (str (:text-muted styles/tokens) "15"))
                      :color (case s
                               "budget-exhausted" (:warning styles/tokens)
                               "error" (:danger styles/tokens)
                               (:text-muted styles/tokens))}
              :title s}
       (case s "budget-exhausted" "incomplete" "error" "error" s)])))

(defn- feedback-link [session-id current-feedback feedback-open?]
  (cond
    (= "negative" (str current-feedback))
    [:span {:style {:font-size "11px" :color (:text-muted styles/tokens)
                    :font-style "italic"}}
     "Feedback submitted"]

    feedback-open?
    [:div {:style {:margin-top "4px"}}
     [:div {:style {:display "flex" :gap "8px" :align-items "flex-end"}}
      [:textarea {:on {:input [:action/ask-feedback-text-input]}
                  :placeholder "What was wrong with this answer?"
                  :aria-label "Feedback on answer quality"
                  :rows 2
                  :style {:flex 1
                          :padding "8px 10px"
                          :background "rgba(15,20,30,0.75)"
                          :border (str "1px solid " (:border styles/tokens))
                          :border-radius (:radius styles/tokens)
                          :color (:text-primary styles/tokens)
                          :font-size "12px"
                          :resize "none"}}]
      [:button {:on {:click [:action/ask-feedback-submit session-id]}
                :style {:background (:accent styles/tokens)
                        :border "none"
                        :border-radius (:radius styles/tokens)
                        :color "#fff"
                        :cursor "pointer"
                        :font-size "11px"
                        :padding "6px 12px"
                        :white-space "nowrap"}}
       "Send"]
      [:button {:on {:click [:action/ask-feedback-cancel]}
                :style {:background "none" :border "none" :cursor "pointer"
                        :font-size "14px" :color (:text-muted styles/tokens)}}
       "\u00D7"]]]

    :else
    [:button {:on {:click [:action/ask-feedback-open]}
              :style {:background "none" :border "none" :cursor "pointer"
                      :font-size "11px" :color (:text-muted styles/tokens)
                      :padding "0"}}
     "Could be better?"]))

(defn- expanded-session-detail [detail]
  (when detail
    [:div {:style {:padding "12px 0"
                   :animation "fadeSlideIn 0.2s ease"}}
     (when-let [answer (:answer detail)]
       [:div {:style {:padding "12px 16px"
                      :background "rgba(15,20,30,0.75)"
                      :border (str "1px solid " (:border styles/tokens))
                      :border-radius (:radius styles/tokens)
                      :position "relative"}}
        [:button {:on {:click [:action/copy-to-clipboard answer]}
                  :style {:position "absolute" :top "10px" :right "10px"
                          :background "rgba(255,255,255,0.05)"
                          :border "1px solid rgba(255,255,255,0.08)"
                          :border-radius "6px"
                          :cursor "pointer"
                          :color "rgba(255,255,255,0.4)"
                          :font-size "11px"
                          :padding "4px 10px"}
                  :title "Copy to clipboard"}
         "Copy"]
        (md/render-markdown answer)])]))

(defn- past-session-item [{:keys [id question status duration-ms iterations
                                  started-at channel feedback]}
                          expanded? detail state]
  [:div {:style {:padding "10px 0"
                 :border-bottom (str "1px solid " (str (:border styles/tokens) "60"))}}
   [:button {:on {:click [:action/ask-expand-session id]}
             :aria-expanded (boolean expanded?)
             :style {:display "flex" :align-items "baseline" :gap "12px"
                     :cursor "pointer" :width "100%"
                     :background "none" :border "none" :padding 0
                     :text-align "left" :color "inherit" :font "inherit"}}
    [:div {:style {:flex 1 :min-width 0}}
     [:div {:style {:font-size "13px"
                    :color (:text-primary styles/tokens)
                    :white-space "nowrap"
                    :overflow "hidden"
                    :text-overflow "ellipsis"}}
      question]
     [:div {:style {:display "flex" :gap "8px" :margin-top "3px"
                    :font-size "11px" :color (:text-muted styles/tokens)}}
      (when started-at [:span (format-ago started-at)])
      (when channel [:span (name channel)])
      (when iterations [:span (str iterations " steps")])
      (when duration-ms [:span (str (.toFixed (/ duration-ms 1000.0) 1) "s")])]]
    (status-badge status)
    [:span {:style {:font-size "12px" :color (:text-muted styles/tokens)}}
     (if expanded? "\u25B2" "\u25BC")]]
   ;; Expanded detail
   (when expanded?
     [:div
      (if detail
        (expanded-session-detail detail)
        [:div {:style {:padding "12px 0" :color (:text-muted styles/tokens)
                       :font-size "12px"}} "Loading..."])
      ;; Feedback
      [:div {:style {:padding-top "8px"}}
       (feedback-link id feedback
                      (= id (:ask/feedback-open-id state)))]])])

;; Question catalog lives in state.cljs (suggestion-catalog)

(defn- suggestion-ticker [visible-set paused?]
  (when (seq visible-set)
    (let [items (vec (concat visible-set visible-set))]
      [:div {:role "region"
             :aria-label "Suggested questions"
             :on {:mouseenter [:action/ask-ticker-pause true]
                  :mouseleave [:action/ask-ticker-pause false]}
             :style {:width "100%"
                     :margin-top "20px"
                     :overflow "hidden"
                     :position "relative"
                     :-webkit-mask-image "linear-gradient(to right, transparent 0%, black 8%, black 92%, transparent 100%)"
                     :mask-image "linear-gradient(to right, transparent 0%, black 8%, black 92%, transparent 100%)"}}
       [:div {:class (when paused? "ticker-paused")
              :style {:display "flex"
                      :gap "16px"
                      :white-space "nowrap"
                      :animation (str "tickerScroll " (* (count visible-set) 10) "s linear infinite")
                      :width "max-content"}}
        (let [n (count visible-set)]
          (for [[i q] (map-indexed vector items)]
            (let [duplicate? (>= i n)]
              [:button {:key (str i "-" q)
                        :on {:click [:action/ask-run-suggestion q]}
                        :tabindex (if duplicate? -1 0)
                        :aria-hidden (when duplicate? "true")
                        :aria-label (str "Ask: " q)
                        :style {:background "rgba(15,20,30,0.75)"
                                :border (str "1px solid " (:border styles/tokens))
                                :border-radius "16px"
                                :color (:text-secondary styles/tokens)
                                :cursor "pointer"
                                :font-size "12px"
                                :padding "6px 16px"
                                :white-space "nowrap"
                                :flex-shrink 0}}
               q])))]])))

(defn ask-view [state]
  (let [{:keys [ask/query ask/loading? ask/result ask/history ask/progress
                ask/steps ask/past-sessions ask/expanded-session
                ask/expanded-detail ask/completions ask/completion-idx
                ask/reasoning-expanded? ask/visible-suggestions
                databases/list db-name]} state
        has-results? (or (seq history) result loading?)]
    [:div {:style {:display "flex"
                   :flex-direction "column"
                   :align-items "center"
                   :width "100%"}}
     [:div {:style {:width "100%"
                    :position "relative"}}
      [:input {:type "text"
               :id "ask-input"
               :placeholder "Ask anything about your codebase..."
               :value (or query "")
               :on {:input [:action/ask-input]
                    :keydown [:action/ask-keydown]}
               :role "combobox"
               :aria-expanded (boolean (seq completions))
               :aria-autocomplete "list"
               :aria-haspopup "listbox"
               :aria-controls "ask-completions"
               :style {:width "100%"
                       :padding "14px 18px"
                       :padding-right "44px"
                       :background "rgba(15,20,30,0.8)"
                       :backdrop-filter "blur(12px)"
                       :-webkit-backdrop-filter "blur(12px)"
                       :border "1px solid rgba(255,255,255,0.08)"
                       :border-radius "12px"
                       :color "#e6edf3"
                       :font-size "15px"}}]
      ;; Submit / Stop button
      (if loading?
        [:button {:on {:click [:action/ask-cancel]}
                  :style {:position "absolute" :right "12px" :top "50%"
                          :transform "translateY(-50%)"
                          :background "none" :border "none" :cursor "pointer"
                          :color (:danger styles/tokens) :font-size "13px"
                          :padding "4px 8px"
                          :z-index 2}}
         "Stop"]
        (when (seq query)
          [:button {:on {:click [:action/ask-submit]}
                    :style {:position "absolute" :right "10px" :top "50%"
                            :transform "translateY(-50%)"
                            :background "none" :border "none" :cursor "pointer"
                            :color (:accent styles/tokens) :font-size "16px"
                            :padding "4px 8px"
                            :z-index 2}}
           "\u2192"]))
      ;; @-mention autocomplete dropdown
      (when (seq completions)
        [:div {:id "ask-completions"
               :role "listbox"
               :style {:position "absolute"
                       :top "100%"
                       :left 0
                       :right 0
                       :margin-top "4px"
                       :background "rgba(10,14,23,0.95)"
                       :backdrop-filter "blur(12px)"
                       :-webkit-backdrop-filter "blur(12px)"
                       :border (str "1px solid " (:border styles/tokens))
                       :border-radius (:radius styles/tokens)
                       :box-shadow "0 4px 12px rgba(0,0,0,0.4)"
                       :max-height "240px"
                       :overflow-y "auto"
                       :z-index 200}}
         (for [[i item] (map-indexed vector completions)]
           [:div {:key (:value item)
                  :id (str "ask-completion-" i)
                  :role "option"
                  :aria-label (str (:type item) " " (:value item))
                  :aria-selected (= i (or completion-idx 0))
                  :on {:click [:action/ask-complete (:value item)]}
                  :style {:padding "8px 14px"
                          :cursor "pointer"
                          :display "flex"
                          :align-items "center"
                          :gap "10px"
                          :background (when (= i (or completion-idx 0))
                                        (:bg-tertiary styles/tokens))
                          :font-size "13px"}}
            [:span {:style {:font-size "10px"
                            :color (:text-muted styles/tokens)
                            :min-width "44px"
                            :text-transform "uppercase"}}
             (:type item)]
            [:span {:style {:font-family (:font-mono styles/tokens)
                            :color (:text-primary styles/tokens)
                            :white-space "nowrap"
                            :overflow "hidden"
                            :text-overflow "ellipsis"}}
             (:value item)]
            (when-let [label (:label item)]
              [:span {:style {:color (:text-secondary styles/tokens)
                              :font-size "12px"
                              :overflow "hidden"
                              :text-overflow "ellipsis"
                              :white-space "nowrap"}}
               label])])])]
     ;; Hint
     [:div {:style {:width "100%" :margin-top "6px"
                    :font-size "11px" :color (:text-muted styles/tokens)}}
      "Type @ to reference a file or author. Press Enter to ask. "
      (let [mac? (and (exists? js/navigator)
                      (re-find #"Mac" (.-platform js/navigator)))]
        [:span {:style {:color "rgba(255,255,255,0.2)"}}
         (if mac? "\u2318K to focus" "Ctrl+K to focus")])]
     ;; Scrollable content below input (doesn't clip the completions dropdown)
     [:div {:style {:max-height "55vh" :overflow-y "auto" :width "100%"}}
      (when (and loading? (or (seq steps) progress))
        (reasoning-trace steps progress reasoning-expanded?))
      (when (seq history)
        (into [:div {:style {:width "100%" :margin-top "24px"}}]
              (concat
               (for [[i item] (map-indexed vector (reverse history))]
                 [:div {:key i} (history-item item)])
               (let [last-steps (filterv #(= "step" (:type %)) (or (:ask/last-steps state) []))]
                 (when (and (seq last-steps) (not loading?))
                   (let [showing? (:ask/show-post-reasoning? state)]
                     [[:div {:style {:margin-top "4px"}}
                       [:button {:on {:click [:action/ask-toggle-post-reasoning]}
                                 :style {:background "none" :border "none" :cursor "pointer"
                                         :font-size "12px" :color (:text-muted styles/tokens)
                                         :padding "0" :display "flex" :align-items "center"
                                         :gap "4px"}}
                        [:span (if showing? "\u25BC" "\u25B6")]
                        (str "Show reasoning (" (count last-steps) " steps)")]
                       (when showing?
                         (reasoning-trace last-steps nil reasoning-expanded?))]])))
               [[:div {:style {:margin-top "12px" :text-align "center"}}
                 [:button {:on {:click [:action/ask-clear]}
                           :style {:background "none" :border "none" :cursor "pointer"
                                   :font-size "13px" :color (:text-muted styles/tokens)
                                   :padding "4px 12px"}}
                  "New question"]]])))]]))

(defn past-questions-panel [state]
  (let [{:keys [ask/past-sessions ask/expanded-session ask/expanded-detail]} state]
    (when (seq past-sessions)
      (let [expanded-list? (:ask/history-open? state)]
        [:div
         [:button {:on {:click [:action/ask-toggle-history]}
                   :style {:display "flex" :align-items "center" :gap "6px"
                           :background "none" :border "none" :cursor "pointer"
                           :padding "0" :margin-bottom "8px"
                           :font-size "11px"
                           :color "rgba(255,255,255,0.3)"}}
          [:span (if expanded-list? "\u25BC" "\u25B6")]
          (str "Previous questions (" (count past-sessions) ")")]
         (when expanded-list?
           (let [limit     (or (:ask/history-limit state) 5)
                 visible   (take limit past-sessions)
                 has-more? (> (count past-sessions) limit)]
             [:div
              (for [[i session] (map-indexed vector visible)]
                [:div {:key (or (:id session) i)}
                 (past-session-item session
                                    (= (:id session) expanded-session)
                                    (when (= (:id session) expanded-session)
                                      expanded-detail)
                                    state)])
              (when has-more?
                [:button {:on {:click [:action/ask-load-more-history]}
                          :style {:background "none" :border "none" :cursor "pointer"
                                  :font-size "12px" :color (:accent styles/tokens)
                                  :padding "8px 0"}}
                 (str "Show more (" (- (count past-sessions) limit) " older)")])]))]))))
