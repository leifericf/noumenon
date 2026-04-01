(ns noumenon.ui.state
  (:require [noumenon.ui.graph.data :as gdata]
            [noumenon.ui.http :as http]
            [noumenon.ui.styles :as styles]))

(declare dispatch!)

(defonce ^:private active-sse-cancels (atom {}))

(defonce app-state
  (atom {:route            :ask
         :db-name          nil
         :daemon/port      nil
         :daemon/status    :unknown
         :ask/query        ""
         :ask/loading?     false
         :ask/result       nil
         :ask/history      []
         :databases/list   []
         :databases/operations {}
         :databases/loading? false
         :databases/error  nil
         :backends         [{:name "Local" :url "auto" :token nil}]
         :active-backend   "Local"
         :settings/loaded? false
         :toasts           []
         ;; Three-level graph
         :graph/depth           :components
         :graph/comp-nodes      nil
         :graph/comp-edges      nil
         :graph/expanded-comp   nil
         :graph/expanded-file   nil
         :graph/file-cache      {}
         :graph/segment-cache   {}
         :graph/card-cache      {}
         :graph/breadcrumb      []
         :graph/all-import-edges nil
         :graph/comp-authors    {}
         :graph/expand-time     nil
         ;; Ask panel position (draggable)
         :ask/panel-x           nil    ;; nil = centered
         :ask/panel-y           nil}))

;; --- Pure event handlers ---
;; Each returns {:state new-state} or {:state new-state :fx [effects...]}.
;; Effects are data: [:http/get path callbacks], [:http/post ...], [:dispatch event], etc.
;; This keeps handlers pure and testable.

(defmulti handle-event (fn [_state [action & _]] action))

(defmethod handle-event :default [state event]
  {:state state :fx [[:log/warn (str "Unhandled event: " (pr-str event))]]})

(defmethod handle-event :action/navigate [state [_ route]]
  {:state (assoc state :route route)
   :fx    [[:dom/set-hash (str "/" (name route))]]})

(defmethod handle-event :action/route-changed [state [_ route]]
  ;; From hash-change listener — don't set hash back (avoid loop)
  {:state (assoc state :route route)})

(defmethod handle-event :action/sidebar-toggle [state _]
  (let [new-val (not (:sidebar/collapsed? state))]
    {:state (assoc state :sidebar/collapsed? new-val)
     :fx    [[:dispatch [:action/setting-save "sidebar-collapsed" new-val]]]}))

(defmethod handle-event :action/theme-toggle [state _]
  (let [new-theme (if (= :dark (:theme state :dark)) :light :dark)]
    {:state (assoc state :theme new-theme)
     :fx    [[:theme/set new-theme]
             [:dispatch [:action/setting-save "theme" new-theme]]]}))

;; --- Database events ---

(defmethod handle-event :action/db-refresh [state _]
  {:state (assoc state :databases/loading? true :databases/error nil)
   :fx    [[:http/get "/api/databases"
            {:on-ok :action/db-loaded :on-error :action/db-error}]]})

(defmethod handle-event :action/db-loaded [state [_ data]]
  (let [had-db? (some? (:db-name state))
        new-db  (or (:db-name state) (:name (first data)))]
    {:state (assoc state :databases/list data :databases/loading? false
                   :daemon/status :connected
                   :db-name new-db)
     ;; Load graph once we have a db-name for the first time
     :fx    (when (and (not had-db?) new-db)
              [[:dispatch [:action/graph-load]]])}))

(defmethod handle-event :action/db-error [state [_ error]]
  {:state (assoc state :databases/error error :databases/loading? false
                 :daemon/status :error)
   :fx    [[:dispatch [:action/toast {:message error :type :error}]]]})

(defmethod handle-event :action/db-delete [state [_ db-name]]
  {:state state
   :fx    [[:confirm/then
            (str "Delete database \"" db-name "\"? This cannot be undone.")
            [:action/db-delete-confirmed db-name]]]})

(defmethod handle-event :action/db-delete-confirmed [state [_ db-name]]
  {:state state
   :fx    [[:http/delete (str "/api/databases/" (js/encodeURIComponent db-name))
            {:on-ok :action/db-refresh :on-error :action/db-error}]]})

(defmethod handle-event :action/db-operation [state [_ db-name op]]
  {:state (assoc-in state [:databases/operations db-name] {:op op :progress nil})
   :fx    [[:http/sse (str "/api/" (name op))
            {:repo_path db-name}
            {:on-progress [:action/db-progress db-name op]
             :on-result   [:action/db-op-result db-name op]
             :on-done     [:action/db-op-done db-name]
             :on-error    :action/db-error}]]})

(defmethod handle-event :action/db-progress [state [_ db-name op progress]]
  {:state (assoc-in state [:databases/operations db-name] {:op op :progress progress})})

(defmethod handle-event :action/db-op-result [state [_ db-name _op _result]]
  {:state (update state :databases/operations dissoc db-name)})

(defmethod handle-event :action/db-op-done [state [_ db-name]]
  {:state (update state :databases/operations dissoc db-name)
   :fx    [[:dispatch [:action/db-refresh]]]})

(defmethod handle-event :action/db-new-repo-set [state [_ value]]
  {:state (assoc state :databases/new-repo value)})

(defmethod handle-event :action/db-import-new [state _]
  (let [repo-path (:databases/new-repo state)]
    (if (seq repo-path)
      {:state (assoc state :databases/new-repo "")
       :fx    [[:dispatch [:action/db-operation repo-path :import]]]}
      {:state state})))

;; --- Ask events ---

(defmethod handle-event :action/ask-load-history [state _]
  {:state state
   :fx    [[:http/get "/api/ask/sessions"
            {:on-ok :action/ask-history-loaded}]]})

(defmethod handle-event :action/ask-history-loaded [state [_ sessions]]
  {:state (assoc state :ask/past-sessions (or sessions []))})

(defmethod handle-event :action/ask-toggle-history [state _]
  {:state (update state :ask/history-open? not)})

(defmethod handle-event :action/ask-load-more-history [state _]
  {:state (update state :ask/history-limit (fnil + 5) 5)})

(defmethod handle-event :action/ask-toggle-reasoning [state _]
  {:state (update state :ask/reasoning-expanded? not)})

(defmethod handle-event :action/ask-toggle-post-reasoning [state _]
  {:state (update state :ask/show-post-reasoning? not)})

(defmethod handle-event :action/ask-clear [state _]
  {:state (assoc state :ask/history [] :ask/last-steps nil :ask/steps [] :ask/result nil
                 :ask/show-post-reasoning? false :ask/reasoning-expanded? false
                 :ask/expanded-session nil :ask/expanded-detail nil
                 :graph/focused-ids nil)})

(defmethod handle-event :action/ask-panel-move [state [_ {:keys [x y]}]]
  {:state (assoc state :ask/panel-x x :ask/panel-y y)})

(def ^:private suggestion-catalog
  [;; Temporal & churn
   "Which files have had the most bug fixes recently?"
   "What is the code churn rate, and where is it highest?"
   "How has the ratio of feature vs fix work changed over time?"
   "Which commits introduced the most scattered changes?"
   "Did our refactoring efforts pay off in reduced churn?"
   ;; Ownership & expertise
   "Who is the de facto expert for each subsystem?"
   "What is the bus factor for each module?"
   "Which areas have knowledge concentrated in a single person?"
   "Who are the top 3 contributors by commit count?"
   ;; Quality & risk
   "Where do we have the most technical debt?"
   "Which files always change together despite no compile-time dependency?"
   "What is the blast radius of a change to a core module?"
   "Which deprecated functions are still being modified?"
   "Which functions have safety concerns?"
   "Is a given file safe to refactor?"
   ;; Architecture
   "Describe the overall architecture in terms of layers."
   "What design patterns are most prevalent?"
   "What are the main subsystems and how do they depend on each other?"
   "Where would you focus a code review?"
   "Where would you focus a refactoring effort?"
   ;; Code intelligence
   "Which code segments are never called by any other segment?"
   "Which files have the most external dependencies?"
   "Which dependencies are shared across the most files?"
   "Which code segments are pure and side-effect-free?"
   "What percentage of code appears AI-generated?"
   ;; Import graph
   "Which tests should I run after changing a core module?"
   "Which architectural boundaries are being violated by imports?"
   "Which files are the most-imported in the codebase?"
   "Are there any circular import dependencies?"
   "Which files are orphaned — no imports in or out?"
   "Which imports cross directory boundaries?"
   "What is the full transitive impact of changing a widely-imported file?"
   ;; Defects & contributors
   "Which files are defect attractors?"
   "Which user produces the greatest number of bugs?"
   "Who fixed the most bugs?"
   "Which directories have the lowest bus factor?"
   "What is the distribution of commit types?"
   ;; Synthesis
   "What can you infer about development health from fix vs feature commits?"
   "Are there areas where high churn correlates with low code quality?"
   "Which files are most frequently changed together?"
   "Which files are rated as complex or very-complex?"])

(defmethod handle-event :action/ask-ticker-pause [state [_ paused?]]
  {:state (assoc state :ask/ticker-paused? paused?)})

(defmethod handle-event :action/ask-run-suggestion [state [_ question]]
  {:state (assoc state :ask/query question)})

(defmethod handle-event :action/ask-init-suggestions [state _]
  (let [shuffled (sort-by (fn [_] (js/Math.random)) suggestion-catalog)]
    {:state (assoc state :ask/visible-suggestions (vec (take 6 shuffled)))}))

(defmethod handle-event :action/ask-rotate-one-suggestion [state _]
  (let [current  (or (:ask/visible-suggestions state) [])
        used     (set current)
        pool     (remove used suggestion-catalog)
        new-q    (when (seq pool) (nth (vec pool) (rand-int (count pool))))
        slot     (rand-int (count current))]
    (if new-q
      {:state (-> state
                  (assoc-in [:ask/visible-suggestions slot] new-q)
                  (assoc :ask/suggestion-animating slot))}
      {:state state})))

(defmethod handle-event :action/ask-expand-session [state [_ session-id]]
  (if (= session-id (:ask/expanded-session state))
    {:state (dissoc state :ask/expanded-session :ask/expanded-detail)}
    {:state (assoc state :ask/expanded-session session-id)
     :fx    [[:http/get (str "/api/ask/sessions/" (js/encodeURIComponent session-id))
              {:on-ok :action/ask-session-detail-loaded}]]}))

(defmethod handle-event :action/ask-session-detail-loaded [state [_ detail]]
  {:state (assoc state :ask/expanded-detail detail)})

(defmethod handle-event :action/ask-feedback-open [state _]
  {:state (assoc state :ask/feedback-open-id
                 ;; Open for the currently expanded session
                 (:ask/expanded-session state))})

(defmethod handle-event :action/ask-feedback-cancel [state _]
  {:state (dissoc state :ask/feedback-open-id :ask/feedback-text)})

(defmethod handle-event :action/ask-feedback-text-set [state [_ text]]
  {:state (assoc state :ask/feedback-text text)})

(defmethod handle-event :action/ask-feedback-submit [state [_ session-id]]
  (let [comment (:ask/feedback-text state)]
    {:state (dissoc state :ask/feedback-open-id :ask/feedback-text)
     :fx    [[:http/post (str "/api/ask/sessions/" (js/encodeURIComponent session-id) "/feedback")
              {:feedback "negative" :comment comment}
              {:on-ok :action/ask-feedback-saved :on-error :action/toast-error}]]}))

(defmethod handle-event :action/ask-feedback [state [_ session-id feedback comment]]
  {:state state
   :fx    [[:http/post (str "/api/ask/sessions/" (js/encodeURIComponent session-id) "/feedback")
            {:feedback (name feedback) :comment comment}
            {:on-ok :action/ask-feedback-saved :on-error :action/toast-error}]]})

(defmethod handle-event :action/ask-feedback-saved [state [_ _]]
  {:state state
   :fx    [[:dispatch [:action/ask-load-history]]
           [:dispatch [:action/toast {:message "Feedback saved" :type :success}]]]})

(defmethod handle-event :action/ask-reask [state [_ question]]
  {:state (assoc state :ask/query question)})

(defmethod handle-event :action/ask-set-query [state [_ value]]
  ;; Check for @-mention trigger
  (let [at-idx    (.lastIndexOf value "@")
        has-at?   (and (>= at-idx 0)
                       ;; Only trigger if @ is at cursor end (no space after)
                       (not (.includes (.substring value (inc at-idx)) " ")))
        prefix    (when has-at? (.substring value (inc at-idx)))
        db-name   (or (:db-name state) (-> state :databases/list first :name))]
    {:state (cond-> (assoc state :ask/query value)
              (not has-at?) (dissoc :ask/completions :ask/completion-idx)
              has-at?       (assoc :ask/at-prefix prefix :ask/completion-idx 0))
     :fx    (when (and has-at? db-name (>= (count prefix) 0))
              [[:dispatch-later 150
                [:action/ask-fetch-completions db-name prefix]]])}))

(defmethod handle-event :action/ask-fetch-completions [state [_ db-name prefix]]
  ;; Only fetch if the prefix still matches (user might have typed more)
  (let [current-prefix (:ask/at-prefix state)]
    (if (= prefix current-prefix)
      {:state state
       :fx    [[:http/get (str "/api/completions?repo_path="
                               (js/encodeURIComponent db-name)
                               "&prefix=" (js/encodeURIComponent prefix))
                {:on-ok [:action/ask-completions-loaded prefix]}]]}
      {:state state})))

(defmethod handle-event :action/ask-completions-loaded [state [_ prefix items]]
  ;; Only apply if prefix still matches (guards against stale HTTP responses)
  (if (= prefix (:ask/at-prefix state))
    {:state (assoc state :ask/completions items :ask/completion-idx 0)}
    {:state state}))

(defmethod handle-event :action/ask-complete [state [_ value]]
  ;; Replace the @prefix with the completed value
  (let [query  (:ask/query state)
        at-idx (.lastIndexOf query "@")
        before (.substring query 0 at-idx)
        new-q  (str before "@" value " ")]
    {:state (assoc state :ask/query new-q)
     :fx    [[:dispatch [:action/ask-dismiss-completions]]]}))

(defmethod handle-event :action/ask-dismiss-completions [state _]
  {:state (dissoc state :ask/completions :ask/at-prefix :ask/completion-idx)})

(defmethod handle-event :action/ask-completion-nav [state [_ direction]]
  (let [items (or (:ask/completions state) [])
        idx   (or (:ask/completion-idx state) 0)
        new-idx (case direction
                  :up   (max 0 (dec idx))
                  :down (min (dec (count items)) (inc idx))
                  idx)]
    {:state (assoc state :ask/completion-idx new-idx)}))

(defn- extract-mentions
  "Extract @-mentions from a question and build a context suffix."
  [query]
  (let [mentions (re-seq #"@(\S+)" query)]
    (when (seq mentions)
      (str "\n\nReferenced entities: "
           (->> mentions (map second) (interpose ", ") (apply str))))))

(defmethod handle-event :action/ask-submit [state _]
  (let [query    (:ask/query state)
        context  (extract-mentions query)
        full-q   (if context (str query context) query)
        db-name  (or (:db-name state) (-> state :databases/list first :name))]
    (if (and (seq query) db-name (not (:ask/loading? state)))
      {:state (assoc state :ask/loading? true :ask/result nil :ask/progress nil :ask/steps []
                     :ask/completions nil :ask/reasoning-expanded? false
                     :ask/show-post-reasoning? false
                     :graph/focused-ids nil)
       :fx    [[:http/sse "/api/ask"
                {:repo_path db-name :question full-q :channel "ui" :caller "human"}
                {:on-progress [:action/ask-progress]
                 :on-result   [:action/ask-result]
                 :on-done     [:action/noop]
                 :on-error    :action/ask-error}]]}
      {:state state
       :fx    (when-not db-name
                [[:dispatch [:action/toast {:message "No database selected" :type :warning}]]])})))

(defmethod handle-event :action/ask-progress [state [_ progress]]
  (let [step-type (:type progress)]
    {:state (cond-> (assoc state :ask/progress progress)
              (#{"step" "thinking" "done"} step-type)
              (update :ask/steps (fnil conj []) progress))}))

(defn- extract-file-paths
  "Extract strings that look like file paths from step samples and the answer."
  [steps answer]
  (let [all-text (str answer " "
                      (->> steps
                           (filter #(= "step" (:type %)))
                           (mapcat :samples)
                           (interpose " ")
                           (apply str)))
        ;; Match paths like src/foo/bar.clj, test/baz.py, etc.
        matches (re-seq #"[a-zA-Z0-9_./-]+\.[a-zA-Z]{1,6}" all-text)]
    (->> matches
         (filter #(and (> (count %) 3)
                       (.includes % "/")))
         distinct
         vec)))

(defmethod handle-event :action/ask-result [state [_ data]]
  (let [query  (:ask/query state)
        answer (or (:answer data) (pr-str data))
        steps  (:ask/steps state)
        files  (extract-file-paths steps answer)]
    {:state (-> state
                (assoc :ask/loading? false :ask/result nil :ask/query ""
                       :ask/last-steps steps)
                (update :ask/history conj {:question query :answer answer}))
     :fx    (when (seq files)
              [[:dispatch [:action/graph-focus files]]])}))

(defmethod handle-event :action/ask-cancel [state _]
  {:state (assoc state :ask/loading? false :ask/progress nil
                 ;; Preserve partial steps so user can review what ran
                 :ask/last-steps (when (seq (:ask/steps state))
                                   (:ask/steps state))
                 :ask/steps [])
   :fx    [[:sse/cancel "/api/ask"]]})

(defmethod handle-event :action/ask-error [state [_ error]]
  (let [query (:ask/query state)]
    {:state (-> state
                (assoc :ask/loading? false :ask/result nil)
                (update :ask/history conj {:question query
                                           :answer (str "**Error:** " error)}))
     :fx    [[:dispatch [:action/toast {:message (str "Ask failed: " error) :type :error}]]]}))

(defmethod handle-event :action/select-db-value [state [_ db-name]]
  {:state (assoc state :db-name db-name
                 :graph/file-cache {} :graph/segment-cache {} :graph/card-cache {})})

;; --- Schema events ---

(defmethod handle-event :action/schema-tab [state [_ tab]]
  {:state (assoc state :schema/tab tab)})

(defmethod handle-event :action/schema-load [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))]
    {:state state
     :fx    (when db-name
              [[:http/get (str "/api/schema/" (js/encodeURIComponent db-name))
                {:on-ok :action/schema-loaded}]
               [:http/get "/api/queries"
                {:on-ok :action/queries-loaded}]])}))

(defmethod handle-event :action/schema-loaded [state [_ data]]
  {:state (assoc state :schema/attrs (:schema data))})

(defmethod handle-event :action/queries-loaded [state [_ data]]
  {:state (assoc state :schema/queries data)})

(defmethod handle-event :action/schema-select-attr [state [_ ident]]
  {:state (assoc state :schema/selected-attr ident)})

(defmethod handle-event :action/schema-filter-set [state [_ text]]
  {:state (assoc state :schema/filter-text text)})

(defmethod handle-event :action/schema-select-query [state [_ query-name]]
  {:state (assoc state :schema/selected-query query-name
                 :schema/query-results nil)})

(defmethod handle-event :action/query-param-set [state [_ param-name value]]
  {:state (assoc-in state [:schema/query-params param-name] value)})

(defmethod handle-event :action/named-query-run [state [_ query-name]]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))
        params  (or (:schema/query-params state) {})]
    {:state (assoc state :schema/query-loading? true)
     :fx    (when db-name
              [[:http/post "/api/query"
                {:repo_path db-name :query_name query-name :params params}
                {:on-ok :action/named-query-result :on-error :action/named-query-error}]])}))

(defmethod handle-event :action/named-query-result [state [_ data]]
  {:state (assoc state :schema/query-loading? false :schema/query-results data)})

(defmethod handle-event :action/named-query-error [state [_ error]]
  {:state (assoc state :schema/query-loading? false
                 :schema/query-results {:total 0 :results [] :error error})})

;; --- Query workbench events ---

(defmethod handle-event :action/query-editor-set [state [_ text]]
  {:state (assoc state :query/editor text)})

(defmethod handle-event :action/query-history-load [state _]
  {:state state
   :fx    [[:http/get "/api/settings"
            {:on-ok :action/query-history-from-settings}]]})

(defmethod handle-event :action/query-history-from-settings [state [_ settings]]
  {:state (assoc state :query/history (or (get settings "query-history") []))})

(defmethod handle-event :action/query-history-loaded [state [_ data]]
  {:state (assoc state :query/history (or data []))})

(defmethod handle-event :action/query-history-select [state [_ query-text]]
  {:state (assoc state :query/editor query-text)})

(defmethod handle-event :action/query-run [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))
        query   (:query/editor state)]
    {:state (assoc state :query/loading? true :query/error nil)
     :fx    (when (and db-name (seq query))
              [[:http/post "/api/query-raw"
                {:repo_path db-name :query query}
                {:on-ok :action/query-result :on-error :action/query-error}]])}))

(defmethod handle-event :action/query-result [state [_ data]]
  (let [query-text (:query/editor state)
        history    (or (:query/history state) [])
        updated    (->> (conj history query-text) distinct (take-last 50) vec)]
    {:state (assoc state :query/loading? false :query/results data
                   :query/error nil :query/history updated)
     :fx    [[:dispatch [:action/setting-save "query-history" updated]]]}))

(defmethod handle-event :action/query-error [state [_ error]]
  {:state (assoc state :query/loading? false :query/error error)})

;; --- Graph events (three-level drill-down) ---

(defn- db-name-from [state]
  (or (:db-name state) (-> state :databases/list first :name)))

(defmethod handle-event :action/graph-load [state _]
  (let [db-name (db-name-from state)]
    (if-not db-name
      {:state state}
      {:state (assoc state :graph/loading? true :graph/selected nil
                     :graph/depth :components :graph/expanded-comp nil
                     :graph/expanded-file nil :graph/breadcrumb [])
       :fx [[:http/post "/api/query"
             {:repo_path db-name :query_name "components" :limit 100}
             {:on-ok :action/graph-comp-data :on-error :action/graph-load-error}]
            [:http/post "/api/query"
             {:repo_path db-name :query_name "cross-component-imports" :limit 500}
             {:on-ok :action/graph-comp-edges-data :on-error :action/graph-load-error}]
            [:http/post "/api/query"
             {:repo_path db-name :query_name "component-churn" :limit 100}
             {:on-ok :action/graph-comp-churn-data :on-error :action/graph-load-error}]
            ;; Hotspots for file-level churn sizing
            [:http/post "/api/query"
             {:repo_path db-name :query_name "hotspots" :limit 500}
             {:on-ok :action/graph-hotspots}]]})))

(defmethod handle-event :action/graph-load-error [state [_ error]]
  {:state (assoc state :graph/loading? false)
   :fx [[:dispatch [:action/toast {:message (str "Graph load failed: " error) :type :error}]]]})

(defmethod handle-event :action/graph-hotspots [state [_ data]]
  {:state (assoc state :graph/hotspots (:results data))})

;; --- Component data accumulation (wait for all 3 before building) ---

(defn- comp-data-ready? [state]
  (and (:graph/raw-comp-data state)
       (:graph/raw-comp-edges state)
       (:graph/raw-comp-churn state)))

(defmethod handle-event :action/graph-comp-data [state [_ data]]
  (let [state (assoc state :graph/raw-comp-data (:results data))]
    (if (comp-data-ready? state)
      {:state state :fx [[:dispatch [:action/graph-comp-build]]]}
      {:state state})))

(defmethod handle-event :action/graph-comp-edges-data [state [_ data]]
  (let [state (assoc state :graph/raw-comp-edges (:results data))]
    (if (comp-data-ready? state)
      {:state state :fx [[:dispatch [:action/graph-comp-build]]]}
      {:state state})))

(defmethod handle-event :action/graph-comp-churn-data [state [_ data]]
  (let [state (assoc state :graph/raw-comp-churn (:results data))]
    (if (comp-data-ready? state)
      {:state state :fx [[:dispatch [:action/graph-comp-build]]]}
      {:state state})))

(defmethod handle-event :action/graph-all-edges-cached [state [_ data]]
  {:state (assoc state :graph/all-import-edges (:results data))})

(defmethod handle-event :action/graph-comp-build [state _]
  (let [nodes (vec (gdata/build-component-nodes
                    (:graph/raw-comp-data state)
                    (:graph/raw-comp-churn state)))
        edges (vec (gdata/build-component-edges
                    (:graph/raw-comp-edges state)))]
    {:state (assoc state
                   :graph/comp-nodes nodes :graph/comp-edges edges
                   :graph/nodes nodes :graph/edges edges
                   :graph/loading? false
                   ;; Clean up raw data
                   :graph/raw-comp-data nil :graph/raw-comp-edges nil
                   :graph/raw-comp-churn nil)}))

;; --- Expand/collapse ---

(defmethod handle-event :action/graph-expand-component [state [_ {:keys [id cx cy]}]]
  (let [comp-name id
        db-name   (db-name-from state)
        cached    (get-in state [:graph/file-cache comp-name])]
    (if cached
      {:state (assoc state :graph/depth :files :graph/expanded-comp comp-name
                     :graph/expanded-file nil :graph/breadcrumb [comp-name]
                     :graph/selected nil :graph/node-card nil
                     :graph/expand-time (.now js/Date)
                     :graph/expand-pos {:cx (or cx 400) :cy (or cy 300)})
       :fx [[:dispatch [:action/graph-file-cluster-ready comp-name]]]}
      {:state (assoc state :graph/depth :files :graph/expanded-comp comp-name
                     :graph/expanded-file nil :graph/breadcrumb [comp-name]
                     :graph/selected nil :graph/node-card nil :graph/loading? true
                     :graph/expand-time (.now js/Date)
                     :graph/expand-pos {:cx (or cx 400) :cy (or cy 300)})
       :fx (cond-> [[:http/post "/api/query"
                     {:repo_path db-name :query_name "component-files"
                      :params {:component-name comp-name} :limit 200}
                     {:on-ok [:action/graph-comp-files-loaded comp-name]
                      :on-error :action/graph-load-error}]
                    [:http/post "/api/query"
                     {:repo_path db-name :query_name "component-authors"
                      :params {:component-name comp-name} :limit 20}
                     {:on-ok [:action/graph-comp-authors-loaded comp-name]}]]
              ;; Lazy-load import edges on first expand
             (nil? (:graph/all-import-edges state))
             (conj [:http/post "/api/query"
                    {:repo_path db-name :query_name "all-import-edges" :limit 5000}
                    {:on-ok :action/graph-all-edges-cached
                     :on-error :action/graph-load-error}]))})))

(defmethod handle-event :action/graph-comp-files-loaded [state [_ comp-name data]]
  (let [{:keys [cx cy]} (or (:graph/expand-pos state) {:cx 400 :cy 300})
        churn-map (into {} (map (juxt first second))
                        (or (:graph/hotspots state) []))
        nodes     (vec (gdata/build-file-cluster-nodes (:results data) churn-map cx cy))
        file-ids  (set (map :id nodes))
        edges     (->> (:graph/all-import-edges state)
                       (keep (fn [[from to]]
                               (when (and (file-ids from) (file-ids to))
                                 {:source from :target to})))
                       vec)]
    {:state (-> state
                (assoc-in [:graph/file-cache comp-name] {:nodes nodes :edges edges})
                (assoc :graph/loading? false))
     :fx [[:dispatch [:action/graph-file-cluster-ready comp-name]]]}))

(defmethod handle-event :action/graph-comp-authors-loaded [state [_ comp-name data]]
  {:state (assoc-in state [:graph/comp-authors comp-name] (:results data))})

(defmethod handle-event :action/graph-file-cluster-ready [state [_ comp-name]]
  (let [cached (get-in state [:graph/file-cache comp-name])]
    ;; Show only the expanded component's files — hide other components
    {:state (assoc state
                   :graph/nodes (:nodes cached)
                   :graph/edges (:edges cached))}))

(defmethod handle-event :action/graph-expand-file [state [_ {:keys [id cx cy]}]]
  (let [file-path id
        db-name   (db-name-from state)
        cached    (get-in state [:graph/segment-cache file-path])
        pos       {:cx (or cx 400) :cy (or cy 300)}]
    (if cached
      {:state (assoc state :graph/depth :segments :graph/expanded-file file-path
                     :graph/breadcrumb [(:graph/expanded-comp state) file-path]
                     :graph/selected nil :graph/node-card nil
                     :graph/expand-time (.now js/Date)
                     :graph/expand-pos pos)
       :fx [[:dispatch [:action/graph-segment-cluster-ready file-path]]]}
      {:state (assoc state :graph/depth :segments :graph/expanded-file file-path
                     :graph/breadcrumb [(:graph/expanded-comp state) file-path]
                     :graph/selected nil :graph/node-card nil :graph/loading? true
                     :graph/expand-time (.now js/Date)
                     :graph/expand-pos pos)
       :fx [[:http/post "/api/query"
             {:repo_path db-name :query_name "file-segments"
              :params {:file-path file-path} :limit 500}
             {:on-ok [:action/graph-segments-loaded file-path]
              :on-error :action/graph-load-error}]
            [:http/post "/api/query"
             {:repo_path db-name :query_name "file-segment-issues"
              :params {:file-path file-path} :limit 500}
             {:on-ok [:action/graph-segment-smells-loaded file-path]
              :on-error :action/graph-load-error}]
            [:http/post "/api/query"
             {:repo_path db-name :query_name "file-segment-safety"
              :params {:file-path file-path} :limit 500}
             {:on-ok [:action/graph-segment-safety-loaded file-path]
              :on-error :action/graph-load-error}]
            [:http/post "/api/query"
             {:repo_path db-name :query_name "file-segment-calls"
              :params {:file-path file-path} :limit 500}
             {:on-ok [:action/graph-segment-calls-loaded file-path]
              :on-error :action/graph-load-error}]]})))

;; --- Segment data accumulation ---

(defn- seg-data-ready? [state file-path]
  (let [raw (get-in state [:graph/raw-segments file-path])]
    (every? #(contains? raw %) [:segments :smells :safety :calls])))

(defmethod handle-event :action/graph-segments-loaded [state [_ file-path data]]
  (let [state (assoc-in state [:graph/raw-segments file-path :segments] (:results data))]
    (if (seg-data-ready? state file-path)
      {:state state :fx [[:dispatch [:action/graph-segment-build file-path]]]}
      {:state state})))

(defmethod handle-event :action/graph-segment-smells-loaded [state [_ file-path data]]
  (let [state (assoc-in state [:graph/raw-segments file-path :smells] (:results data))]
    (if (seg-data-ready? state file-path)
      {:state state :fx [[:dispatch [:action/graph-segment-build file-path]]]}
      {:state state})))

(defmethod handle-event :action/graph-segment-safety-loaded [state [_ file-path data]]
  (let [state (assoc-in state [:graph/raw-segments file-path :safety] (:results data))]
    (if (seg-data-ready? state file-path)
      {:state state :fx [[:dispatch [:action/graph-segment-build file-path]]]}
      {:state state})))

(defmethod handle-event :action/graph-segment-calls-loaded [state [_ file-path data]]
  (let [state (assoc-in state [:graph/raw-segments file-path :calls] (:results data))]
    (if (seg-data-ready? state file-path)
      {:state state :fx [[:dispatch [:action/graph-segment-build file-path]]]}
      {:state state})))

(defmethod handle-event :action/graph-segment-build [state [_ file-path]]
  (let [raw       (get-in state [:graph/raw-segments file-path])
        {:keys [cx cy]} (or (:graph/expand-pos state) {:cx 400 :cy 300})
        nodes     (vec (gdata/build-segment-nodes
                        (:segments raw) (:smells raw) (:safety raw) cx cy))
        seg-names (set (map :id nodes))
        edges     (vec (gdata/build-segment-edges (:calls raw) seg-names))]
    {:state (-> state
                (assoc-in [:graph/segment-cache file-path] {:nodes nodes :edges edges})
                (update :graph/raw-segments dissoc file-path)
                (assoc :graph/loading? false))
     :fx [[:dispatch [:action/graph-segment-cluster-ready file-path]]]}))

(defmethod handle-event :action/graph-segment-cluster-ready [state [_ file-path]]
  (let [cached (get-in state [:graph/segment-cache file-path])]
    ;; Show only the expanded file's segments — hide other files
    {:state (assoc state
                   :graph/nodes (:nodes cached)
                   :graph/edges (:edges cached))}))

(defmethod handle-event :action/graph-collapse [state _]
  (case (:graph/depth state)
    :segments
    {:state (assoc state :graph/depth :files :graph/expanded-file nil
                   :graph/breadcrumb [(first (:graph/breadcrumb state))]
                   :graph/selected nil :graph/node-card nil
                   :graph/expand-time (.now js/Date))
     :fx [[:dispatch [:action/graph-file-cluster-ready (:graph/expanded-comp state)]]]}

    :files
    {:state (assoc state :graph/depth :components :graph/expanded-comp nil
                   :graph/expanded-file nil :graph/breadcrumb []
                   :graph/nodes (:graph/comp-nodes state)
                   :graph/edges (:graph/comp-edges state)
                   :graph/selected nil :graph/node-card nil
                   :graph/expand-time nil)}

    {:state state}))

(defmethod handle-event :action/graph-collapse-to-top [state _]
  {:state (assoc state :graph/depth :components :graph/expanded-comp nil
                 :graph/expanded-file nil :graph/breadcrumb []
                 :graph/nodes (:graph/comp-nodes state)
                 :graph/edges (:graph/comp-edges state)
                 :graph/selected nil :graph/node-card nil)})

;; --- Node selection / card ---

(defmethod handle-event :action/graph-select [state [_ node-id]]
  {:state (assoc state :graph/selected node-id
                 :graph/node-card nil :graph/node-card-pos nil)})

(defn- file-card-effects
  "HTTP effects to fetch file card details."
  [db-name id]
  [[:http/post "/api/query"
    {:repo_path db-name :query_name "file-imports"
     :params {:file-path id} :limit 20}
    {:on-ok [:action/graph-node-imports-loaded id]}]
   [:http/post "/api/query"
    {:repo_path db-name :query_name "file-importers"
     :params {:file-path id} :limit 20}
    {:on-ok [:action/graph-node-importers-loaded id]}]
   [:http/post "/api/query"
    {:repo_path db-name :query_name "file-authors"
     :params {:file-path id} :limit 10}
    {:on-ok [:action/graph-node-authors-loaded id]}]
   [:http/post "/api/query"
    {:repo_path db-name :query_name "file-history"
     :params {:file-path id} :limit 3}
    {:on-ok [:action/graph-node-history-loaded id]}]
   [:http/post "/api/query-raw"
    {:repo_path db-name
     :query (pr-str '[:find ?summary ?complexity
                      :in $ ?path
                      :where
                      [?f :file/path ?path]
                      [(get-else $ ?f :sem/summary "") ?summary]
                      [(get-else $ ?f :sem/complexity :unknown) ?complexity]])
     :args [id]}
    {:on-ok [:action/graph-node-meta-loaded id]}]])

(defmethod handle-event :action/graph-select-node [state [_ {:keys [id x y]}]]
  (let [db-name (db-name-from state)
        node    (->> (:graph/nodes state) (filter #(= (:id %) id)) first)
        cached  (get-in state [:graph/card-cache id])]
    (cond
      ;; Cached card
      cached
      {:state (assoc state :graph/selected id
                     :graph/node-card cached
                     :graph/node-card-pos {:x x :y y})}
      ;; Component node — card data is in the node itself
      (= :component (:type node))
      (let [card {:type       :component
                  :summary    (:summary node)
                  :complexity (:complexity node)
                  :category   (:category node)
                  :file-count (:file-count node)}]
        {:state (-> state
                    (assoc :graph/selected id
                           :graph/node-card card
                           :graph/node-card-pos {:x x :y y})
                    (assoc-in [:graph/card-cache id] card))})
      ;; Segment node — card data is in the node itself
      (= :segment (:type node))
      (let [card (assoc (select-keys node [:kind :complexity :visibility :pure?
                                           :ai-likelihood :purpose :error-handling
                                           :args :returns :smells :safety
                                           :line-start :line-end])
                        :type :segment)]
        {:state (-> state
                    (assoc :graph/selected id
                           :graph/node-card card
                           :graph/node-card-pos {:x x :y y})
                    (assoc-in [:graph/card-cache id] card))})
      ;; File node — fetch details
      :else
      {:state (assoc state :graph/selected id
                     :graph/node-card nil
                     :graph/node-card-pos {:x x :y y})
       :fx (when db-name (file-card-effects db-name id))})))

(defn- maybe-cache-file-card
  "Cache file card when both summary and imports are populated."
  [state file-id]
  (let [card (:graph/node-card state)]
    (if (and card (contains? card :summary) (contains? card :imports))
      (assoc-in state [:graph/card-cache file-id] card)
      state)))

(defmethod handle-event :action/graph-node-imports-loaded [state [_ file-id data]]
  (if (= file-id (:graph/selected state))
    {:state (-> state
                (assoc-in [:graph/node-card :imports] (:results data))
                (maybe-cache-file-card file-id))}
    {:state state}))

(defmethod handle-event :action/graph-node-importers-loaded [state [_ file-id data]]
  (if (= file-id (:graph/selected state))
    {:state (assoc-in state [:graph/node-card :importers] (:results data))}
    {:state state}))

(defmethod handle-event :action/graph-node-authors-loaded [state [_ file-id data]]
  (if (= file-id (:graph/selected state))
    {:state (assoc-in state [:graph/node-card :authors] (:results data))}
    {:state state}))

(defmethod handle-event :action/graph-node-history-loaded [state [_ file-id data]]
  (if (= file-id (:graph/selected state))
    {:state (assoc-in state [:graph/node-card :history] (:results data))}
    {:state state}))

(defmethod handle-event :action/graph-node-meta-loaded [state [_ file-id data]]
  (if (= file-id (:graph/selected state))
    (let [row   (first (:results data))
          state (cond-> state
                  (seq (first row))  (assoc-in [:graph/node-card :summary] (first row))
                  (second row)       (assoc-in [:graph/node-card :complexity] (second row)))]
      {:state (maybe-cache-file-card state file-id)})
    {:state state}))

(defmethod handle-event :action/graph-built [state [_ {:keys [nodes edges]}]]
  {:state (assoc state :graph/nodes nodes :graph/edges edges :graph/loading? false)})

(defmethod handle-event :action/graph-filter-dir-set [state [_ dir]]
  {:state (assoc state :graph/filter-dir dir)})

(defmethod handle-event :action/graph-filter-layer-set [state [_ layer]]
  {:state (assoc state :graph/filter-layer (when (seq layer) (keyword layer)))})

(defmethod handle-event :action/copy-to-clipboard [state [_ text]]
  {:state state
   :fx [[:clipboard/write text]]})

(defmethod handle-event :action/ask-about-node [state [_ file-id]]
  {:state (assoc state :ask/query (str "Tell me about @" file-id)
                 :graph/selected nil :graph/node-card nil :graph/node-card-pos nil)
   :fx    [[:dispatch [:action/ask-submit]]]})

(defmethod handle-event :action/graph-focus [state [_ file-paths]]
  {:state (assoc state :graph/focused-ids (set file-paths))})

(defmethod handle-event :action/graph-clear-focus [state _]
  {:state (dissoc state :graph/focused-ids)})

(defmethod handle-event :action/ask-download-csv [state [_ description samples]]
  {:state state
   :fx    [[:download/csv {:filename (str "noumenon-" (-> description
                                                          (.replace #"[^a-zA-Z0-9]+" "-")
                                                          (.toLowerCase))
                                          "-" (.getTime (js/Date.)) ".csv")
                           :content (apply str (interpose "\n" samples))}]]})

(defmethod handle-event :action/ask-show-step-on-graph [state [_ samples]]
  ;; Extract file paths from the step's sample data
  (let [text  (apply str (interpose " " samples))
        paths (->> (re-seq #"[a-zA-Z0-9_./-]+\.[a-zA-Z]{1,6}" text)
                   (filter #(and (> (count %) 3) (.includes % "/")))
                   distinct vec)]
    (if (seq paths)
      {:state (assoc state :graph/focused-ids (set paths))}
      {:state state})))

;; --- History events ---

(defmethod handle-event :action/history-refresh [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))]
    {:state (assoc state :history/loading? true)
     :fx    (when db-name
              [[:http/post "/api/query"
                {:repo_path db-name :query_name "recent-commits" :limit 100}
                {:on-ok :action/history-loaded :on-error :action/toast-error}]])}))

(defmethod handle-event :action/history-loaded [state [_ data]]
  {:state (assoc state :history/loading? false :history/commits (:results data))})

;; --- Benchmark events ---

(defmethod handle-event :action/bench-refresh [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))]
    (if-not db-name
      {:state state}
      {:state (assoc state :bench/loading? true)
       :fx    [[:http/get (str "/api/benchmark/results?repo_path="
                               (js/encodeURIComponent db-name))
                {:on-ok :action/bench-runs-loaded
                 :on-error :action/bench-load-error}]]})))

(defmethod handle-event :action/bench-runs-loaded [state [_ data]]
  {:state (assoc state :bench/loading? false
                 :bench/runs (if (sequential? data) data (when data [data])))})

(defmethod handle-event :action/bench-load-error [state [_ _error]]
  ;; No benchmark runs is normal — just clear loading, don't toast
  {:state (assoc state :bench/loading? false :bench/runs [])})

(defmethod handle-event :action/bench-run [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))]
    {:state (assoc state :bench/running? true :bench/progress nil)
     :fx    (when db-name
              [[:http/sse "/api/benchmark"
                {:repo_path db-name}
                {:on-progress [:action/bench-progress]
                 :on-result   [:action/bench-result]
                 :on-done     [:action/bench-done]
                 :on-error    :action/toast-error}]])}))

(defmethod handle-event :action/bench-progress [state [_ progress]]
  {:state (assoc state :bench/progress progress)})

(defmethod handle-event :action/bench-result [state [_ _result]]
  {:state (assoc state :bench/running? false :bench/progress nil)})

(defmethod handle-event :action/bench-done [state _]
  {:state (assoc state :bench/running? false :bench/progress nil)
   :fx    [[:dispatch [:action/bench-refresh]]]})

(defmethod handle-event :action/bench-toggle-select [state [_ run-id]]
  (let [selected (or (:bench/selected-ids state) #{})
        updated  (if (contains? selected run-id)
                   (disj selected run-id)
                   (if (>= (count selected) 2)
                     #{run-id}
                     (conj selected run-id)))]
    {:state (assoc state :bench/selected-ids updated)}))

(defmethod handle-event :action/bench-compare [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))
        ids     (vec (:bench/selected-ids state))]
    {:state state
     :fx    (when (and db-name (= 2 (count ids)))
              [[:http/get (str "/api/benchmark/compare?repo_path="
                               (js/encodeURIComponent db-name)
                               "&run_id_a=" (js/encodeURIComponent (first ids))
                               "&run_id_b=" (js/encodeURIComponent (second ids)))
                {:on-ok :action/bench-compare-loaded :on-error :action/toast-error}]])}))

(defmethod handle-event :action/bench-compare-loaded [state [_ data]]
  {:state (assoc state :bench/compare-data data)})

;; --- Introspect events ---

(defmethod handle-event :action/introspect-refresh [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))]
    (if-not db-name
      {:state state}
      {:state (assoc state :introspect/loading? true)
       :fx    [[:http/get (str "/api/introspect/status?repo_path="
                               (js/encodeURIComponent db-name))
                {:on-ok :action/introspect-status-loaded
                 :on-error :action/introspect-load-error}]
               [:http/get (str "/api/introspect/history?repo_path="
                               (js/encodeURIComponent db-name)
                               "&query_name=introspect-runs")
                {:on-ok :action/introspect-history-loaded
                 :on-error :action/introspect-load-error}]]})))

(defmethod handle-event :action/introspect-status-loaded [state [_ data]]
  {:state (assoc state :introspect/status data
                 :introspect/running? (= "running" (:status data))
                 :introspect/loading? false)})

(defmethod handle-event :action/introspect-history-loaded [state [_ data]]
  {:state (assoc state :introspect/history (:results data)
                 :introspect/loading? false)})

(defmethod handle-event :action/introspect-load-error [state [_ _]]
  {:state (assoc state :introspect/loading? false)})

(defmethod handle-event :action/introspect-start [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))]
    {:state (assoc state :introspect/running? true)
     :fx    (when db-name
              [[:http/sse "/api/introspect"
                {:repo_path db-name}
                {:on-progress [:action/introspect-progress]
                 :on-result   [:action/introspect-result]
                 :on-done     [:action/introspect-done]
                 :on-error    :action/toast-error}]])}))

(defmethod handle-event :action/introspect-progress [state [_ progress]]
  {:state (assoc state :introspect/progress progress)})

(defmethod handle-event :action/introspect-result [state [_ result]]
  {:state (assoc state :introspect/running? false :introspect/progress nil
                 :introspect/run-id (:run-id result))})

(defmethod handle-event :action/introspect-done [state _]
  {:state (assoc state :introspect/running? false :introspect/progress nil)
   :fx    [[:dispatch [:action/introspect-refresh]]]})

(defmethod handle-event :action/introspect-stop [state _]
  (let [db-name (or (:db-name state) (-> state :databases/list first :name))
        run-id  (:introspect/run-id state)]
    {:state state
     :fx    (when (and db-name run-id)
              [[:http/post "/api/introspect/stop"
                {:repo_path db-name :run_id run-id}
                {:on-ok :action/introspect-refresh}]])}))

;; --- Backend events (client-side only — managed via config.edn) ---

(defmethod handle-event :action/backends-load [state _]
  ;; Backends are read from config.edn by Electron/CLI and injected
  ;; via window.__NOUMENON_BACKENDS__. No API call needed.
  {:state state
   :fx    [[:backends/load-local]]})

(defmethod handle-event :action/backends-loaded [state [_ {:keys [backends active-backend]}]]
  (let [backends    (or backends [{:name "Local" :url "auto" :token nil}])
        active-name (or active-backend "Local")
        active      (->> backends (filter #(= (:name %) active-name)) first)]
    {:state (assoc state :backends backends :active-backend active-name)
     :fx    (when active
              [[:connection/set active]])}))

(defmethod handle-event :action/backend-switch [state [_ backend-name]]
  (let [backend (->> (:backends state) (filter #(= (:name %) backend-name)) first)]
    (if backend
      {:state (assoc state :active-backend backend-name)
       :fx    [[:connection/set backend]
               [:backends/save-local {:backends (:backends state)
                                      :active-backend backend-name}]
               [:dispatch [:action/db-refresh]]]}
      {:state state})))

(defmethod handle-event :action/backends-save [state [_ backends]]
  {:state (assoc state :backends backends)
   :fx    [[:backends/save-local {:backends backends
                                  :active-backend (:active-backend state)}]]})

(defmethod handle-event :action/noop [state _]
  {:state state})

;; --- Settings events ---

(defmethod handle-event :action/settings-load [state _]
  {:state state
   :fx    [[:http/get "/api/settings"
            {:on-ok :action/settings-loaded}]]})

(defmethod handle-event :action/settings-loaded [state [_ settings]]
  (let [theme    (get settings "theme" :dark)
        sidebar  (get settings "sidebar-collapsed" false)
        db-name  (get settings "default-db")]
    {:state (assoc state
                   :settings/loaded? true
                   :theme theme
                   :sidebar/collapsed? sidebar
                   :db-name (or (:db-name state) db-name))
     :fx    [[:theme/set theme]]}))

(defmethod handle-event :action/setting-save [state [_ key value]]
  {:state state
   :fx    [[:http/post "/api/settings"
            {:key key :value (pr-str value)}
            {:on-ok :action/noop}]]})

;; --- Toast events ---

(defmethod handle-event :action/toast [state [_ {:keys [message type]}]]
  (let [id (str (random-uuid))]
    {:state (update state :toasts conj {:id id :message message :type (or type :error)})
     :fx    [[:dispatch-later 5000 [:action/toast-dismiss id]]]}))

(defmethod handle-event :action/toast-dismiss [state [_ id]]
  {:state (update state :toasts (fn [ts] (vec (remove #(= (:id %) id) ts))))})

(defmethod handle-event :action/toast-error [state [_ error]]
  {:state state
   :fx    [[:dispatch [:action/toast {:message error :type :error}]]]})

;; --- Effect execution (impure shell) ---

(defn- execute-fx!
  "Execute a single effect. This is the only impure code."
  [effect]
  (let [[kind & args] effect]
    (case kind
      :http/get
      (let [[path {:keys [on-ok on-error]}] args]
        (http/api-get! path
                       {:on-ok    #(dispatch! (if (vector? on-ok) (conj on-ok %) [on-ok %]))
                        :on-error #(when on-error (dispatch! [on-error %]))}))

      :http/post
      (let [[path body {:keys [on-ok on-error]}] args]
        (http/api-post! path body
                        {:on-ok    #(dispatch! (if (vector? on-ok) (conj on-ok %) [on-ok %]))
                         :on-error #(when on-error (dispatch! [on-error %]))}))

      :http/delete
      (let [[path {:keys [on-ok on-error]}] args]
        (http/api-delete! path
                          {:on-ok    #(dispatch! (if (vector? on-ok) (conj on-ok %) [on-ok %]))
                           :on-error #(when on-error (dispatch! [on-error %]))}))

      :http/sse
      (let [[path body {:keys [on-progress on-result on-done on-error]}] args]
        ;; Cancel any existing SSE stream for this path
        (when-let [old-cancel (get @active-sse-cancels path)]
          (old-cancel))
        (let [cancel (http/sse-post! path body
                                     {:on-progress #(dispatch! (if (vector? on-progress)
                                                                 (conj on-progress %)
                                                                 [on-progress %]))
                                      :on-result   #(dispatch! (if (vector? on-result)
                                                                 (conj on-result %)
                                                                 [on-result %]))
                                      :on-done     #(do (swap! active-sse-cancels dissoc path)
                                                        (dispatch! (if (vector? on-done) on-done [on-done])))
                                      :on-error    #(do (swap! active-sse-cancels dissoc path)
                                                        (when on-error (dispatch! [on-error %])))})]
          (swap! active-sse-cancels assoc path cancel)))

      :sse/cancel
      (let [[path] args]
        (when-let [cancel-fn (get @active-sse-cancels path)]
          (cancel-fn)
          (swap! active-sse-cancels dissoc path)))

      :confirm/then
      (let [[message on-confirm] args]
        (when (js/confirm message)
          (dispatch! on-confirm)))

      :dispatch
      (let [[event] args]
        (dispatch! event))

      :dispatch-later
      (let [[ms event] args]
        (js/setTimeout #(dispatch! event) ms))

      :dom/set-hash
      (let [[hash] args]
        (set! (.-hash js/location) hash))

      :clipboard/write
      (let [[text] args]
        (-> (.writeText js/navigator.clipboard text)
            (.then #(dispatch! [:action/toast {:message "Copied" :type :info}]))
            (.catch #(dispatch! [:action/toast {:message "Copy failed" :type :error}]))))

      :download/csv
      (let [[{:keys [filename content]}] args
            blob (js/Blob. #js [content] #js {:type "text/csv"})
            url  (.createObjectURL js/URL blob)
            a    (.createElement js/document "a")]
        (set! (.-href a) url)
        (set! (.-download a) filename)
        (.click a)
        (.revokeObjectURL js/URL url))

      :theme/set
      (let [[theme] args]
        (styles/set-theme! theme)
        (styles/inject-styles!))

      :connection/set
      (let [[backend] args
            url (if (= "auto" (:url backend))
                  (str "http://localhost:" (http/detect-port))
                  (:url backend))]
        (reset! http/connection {:url url :token (:token backend)}))

      :backends/load-local
      (let [injected (when (exists? js/window) js/window.__NOUMENON_BACKENDS__)
            data     (if injected
                       (js->clj injected :keywordize-keys true)
                       ;; Fallback: localStorage for browser dev mode
                       (let [raw (.getItem js/localStorage "noumenon-backends")]
                         (when raw (js->clj (js/JSON.parse raw) :keywordize-keys true))))]
        (dispatch! [:action/backends-loaded
                    (or data {:backends [{:name "Local" :url "auto" :token nil}]
                              :active-backend "Local"})]))

      :backends/save-local
      (let [[data] args]
        ;; Save to localStorage (Electron preload can also write config.edn)
        (.setItem js/localStorage "noumenon-backends" (js/JSON.stringify (clj->js data)))
        ;; If Electron IPC is available, also persist to config.edn
        (when (and (exists? js/window) js/window.__noumenon__ js/window.__noumenon__.saveBackends)
          (js/window.__noumenon__.saveBackends (clj->js data))))

      :storage/get
      (let [[key on-ok] args
            raw (.getItem js/localStorage key)
            data (when raw (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                                (catch :default _ nil)))]
        (dispatch! [on-ok data]))

      :storage/set
      (let [[key data] args]
        (.setItem js/localStorage key (js/JSON.stringify (clj->js data))))

      :log/warn
      (let [[msg] args]
        (js/console.warn msg))

      (js/console.warn "Unknown effect:" (pr-str effect)))))

;; --- Dispatch ---

(defn dispatch! [event]
  (let [{:keys [state fx]} (handle-event @app-state event)]
    (reset! app-state state)
    (doseq [effect fx]
      (when effect
        (execute-fx! effect)))))

(defn toast! [message & {:keys [type] :or {type :error}}]
  (dispatch! [:action/toast {:message message :type type}]))

(defn init!
  "Load initial data."
  []
  (dispatch! [:action/backends-load])
  (dispatch! [:action/settings-load])
  (dispatch! [:action/db-refresh])
  ;; Load graph immediately — it's always visible
  (dispatch! [:action/ask-load-history])
  (dispatch! [:action/graph-load]))
