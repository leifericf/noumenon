(ns noumenon.ui.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [noumenon.ui.state :as state]))

(def base-state
  {:route        :ask
   :db-name      "test-db"
   :databases/list [{:name "test-db" :commits 10 :files 5}]
   :ask/query    ""
   :ask/loading? false
   :ask/result   nil
   :ask/history  []
   :toasts       []})

(defn handle [state event]
  (:state (state/handle-event state event)))

(defn effects [state event]
  (:fx (state/handle-event state event)))

;; --- Navigation ---

(deftest navigate-updates-route
  (is (= :graph (:route (handle base-state [:action/navigate :graph])))))

(deftest navigate-emits-hash-effect
  (let [fx (effects base-state [:action/navigate :schema])]
    (is (= [[:dom/set-hash "/schema"]] fx))))

;; --- Ask events ---

(deftest ask-set-query
  (is (= "what?" (:ask/query (handle base-state [:action/ask-set-query "what?"])))))

(deftest ask-submit-sets-loading
  (let [state  (assoc base-state :ask/query "test question")
        result (handle state [:action/ask-submit])]
    (is (:ask/loading? result))
    (is (nil? (:ask/result result)))))

(deftest ask-submit-emits-sse
  (let [state (assoc base-state :ask/query "test question")
        fx    (effects state [:action/ask-submit])]
    (is (= :http/sse (ffirst fx)))))

(deftest ask-submit-no-fx-when-empty-query
  (let [fx (effects base-state [:action/ask-submit])]
    (is (nil? fx))))

(deftest ask-result-appends-history
  (let [state  (assoc base-state :ask/query "my question")
        result (handle state [:action/ask-result {:answer "the answer"}])]
    (is (not (:ask/loading? result)))
    (is (= "" (:ask/query result)))
    (is (= 1 (count (:ask/history result))))
    (is (= "my question" (:question (first (:ask/history result)))))
    (is (= "the answer" (:answer (first (:ask/history result)))))))

(deftest ask-error-shows-error
  (let [result (handle base-state [:action/ask-error "fail"])]
    (is (not (:ask/loading? result)))
    (is (= "Error: fail" (get-in result [:ask/result :answer])))))

;; --- Database events ---

(deftest db-refresh-sets-loading
  (let [result (handle base-state [:action/db-refresh])]
    (is (:databases/loading? result))
    (is (nil? (:databases/error result)))))

(deftest db-refresh-emits-http-get
  (let [fx (effects base-state [:action/db-refresh])]
    (is (= :http/get (ffirst fx)))))

(deftest db-loaded-sets-list
  (let [data   [{:name "repo1"} {:name "repo2"}]
        result (handle base-state [:action/db-loaded data])]
    (is (= data (:databases/list result)))
    (is (not (:databases/loading? result)))))

(deftest db-error-sets-error-and-toasts
  (let [{:keys [state fx]} (state/handle-event base-state [:action/db-error "connection refused"])]
    (is (= "connection refused" (:databases/error state)))
    (is (= [:dispatch [:action/toast {:message "connection refused" :type :error}]]
           (first fx)))))

(deftest db-progress-tracks-operation
  (let [state  (assoc-in base-state [:databases/operations "repo1"] {:op :import :progress nil})
        result (handle state [:action/db-progress "repo1" :import {:current 5 :total 10}])]
    (is (= {:op :import :progress {:current 5 :total 10}}
           (get-in result [:databases/operations "repo1"])))))

(deftest db-op-result-clears-operation
  (let [state  (assoc-in base-state [:databases/operations "repo1"] {:op :import})
        result (handle state [:action/db-op-result "repo1" :import {}])]
    (is (nil? (get-in result [:databases/operations "repo1"])))))

(deftest db-op-done-clears-and-refreshes
  (let [{:keys [state fx]} (state/handle-event
                            (assoc-in base-state [:databases/operations "repo1"] {:op :import})
                            [:action/db-op-done "repo1"])]
    (is (nil? (get-in state [:databases/operations "repo1"])))
    (is (= [:dispatch [:action/db-refresh]] (first fx)))))

;; --- Select DB ---

(deftest select-db-value
  (is (= "other-db" (:db-name (handle base-state [:action/select-db-value "other-db"])))))

;; --- Schema events ---

(deftest schema-tab-switch
  (is (= :workbench (:schema/tab (handle base-state [:action/schema-tab :workbench])))))

(deftest schema-loaded
  (let [attrs [{:ident :file/path :value-type :db.type/string}]]
    (is (= attrs (:schema/attrs (handle base-state [:action/schema-loaded {:schema attrs}]))))))

(deftest schema-select-attr
  (is (= :file/path (:schema/selected-attr (handle base-state [:action/schema-select-attr :file/path])))))

(deftest schema-filter
  (is (= "file" (:schema/filter-text (handle base-state [:action/schema-filter-set "file"])))))

(deftest schema-select-query-clears-results
  (let [state  (assoc base-state :schema/query-results {:total 5})
        result (handle state [:action/schema-select-query "hotspots"])]
    (is (= "hotspots" (:schema/selected-query result)))
    (is (nil? (:schema/query-results result)))))

(deftest named-query-run-emits-post
  (let [fx (effects base-state [:action/named-query-run "hotspots"])]
    (is (= :http/post (ffirst fx)))))

;; --- Query workbench ---

(deftest query-editor-set
  (is (= "[:find ?e]" (:query/editor (handle base-state [:action/query-editor-set "[:find ?e]"])))))

(deftest query-result
  (let [data   {:total 3 :results [[1] [2] [3]]}
        result (handle base-state [:action/query-result data])]
    (is (= data (:query/results result)))
    (is (not (:query/loading? result)))
    (is (nil? (:query/error result)))))

(deftest query-error
  (let [result (handle base-state [:action/query-error "bad query"])]
    (is (= "bad query" (:query/error result)))
    (is (not (:query/loading? result)))))

;; --- Graph events ---

(deftest graph-hotspots-stored
  (let [data [["a.clj" 10] ["b.clj" 5]]]
    (is (= data (:graph/hotspots (handle base-state [:action/graph-hotspots {:results data}]))))))

(deftest graph-select
  (is (= "src/foo.clj" (:graph/selected (handle base-state [:action/graph-select "src/foo.clj"])))))

(deftest graph-mode-switch
  (is (= :co-changes (:graph/mode (handle base-state [:action/graph-mode :co-changes])))))

(deftest graph-mode-emits-dispatch
  (let [fx (effects base-state [:action/graph-mode :co-changes])]
    (is (= :dispatch (ffirst fx)))))

(deftest graph-filter-dir
  (is (= "src/" (:graph/filter-dir (handle base-state [:action/graph-filter-dir-set "src/"])))))

(deftest graph-filter-layer
  (is (= :core (:graph/filter-layer (handle base-state [:action/graph-filter-layer-set "core"])))))

(deftest graph-filter-layer-empty-clears
  (let [state (assoc base-state :graph/filter-layer :core)]
    (is (nil? (:graph/filter-layer (handle state [:action/graph-filter-layer-set ""]))))))

(deftest graph-built
  (let [nodes [{:id "a"} {:id "b"}]
        edges [{:source "a" :target "b"}]
        result (handle base-state [:action/graph-built {:nodes nodes :edges edges}])]
    (is (= nodes (:graph/nodes result)))
    (is (= edges (:graph/edges result)))
    (is (not (:graph/loading? result)))))

(deftest graph-load-emits-queries
  (let [fx (effects base-state [:action/graph-load])]
    (is (= 3 (count fx)))))

;; --- Benchmark events ---

(deftest bench-toggle-select-adds
  (let [result (handle base-state [:action/bench-toggle-select "run-1"])]
    (is (contains? (:bench/selected-ids result) "run-1"))))

(deftest bench-toggle-select-removes
  (let [state (assoc base-state :bench/selected-ids #{"run-1"})]
    (is (not (contains? (:bench/selected-ids (handle state [:action/bench-toggle-select "run-1"])) "run-1")))))

(deftest bench-toggle-select-caps-at-two
  (let [state (assoc base-state :bench/selected-ids #{"run-1" "run-2"})]
    (is (= #{"run-3"} (:bench/selected-ids (handle state [:action/bench-toggle-select "run-3"]))))))

(deftest bench-compare-loaded
  (is (= {:delta 0.1} (:bench/compare-data (handle base-state [:action/bench-compare-loaded {:delta 0.1}])))))

(deftest bench-done-emits-refresh
  (let [fx (effects base-state [:action/bench-done])]
    (is (= [:dispatch [:action/bench-refresh]] (first fx)))))

;; --- Toast events ---

(deftest toast-adds-entry
  (let [result (handle base-state [:action/toast {:message "hi" :type :success}])]
    (is (= 1 (count (:toasts result))))
    (is (= "hi" (:message (first (:toasts result)))))
    (is (= :success (:type (first (:toasts result)))))))

(deftest toast-emits-dismiss-later
  (let [fx (effects base-state [:action/toast {:message "hi"}])]
    (is (= :dispatch-later (ffirst fx)))))

(deftest toast-dismiss-removes
  (let [state (assoc base-state :toasts [{:id "t1" :message "hi"} {:id "t2" :message "bye"}])
        result (handle state [:action/toast-dismiss "t1"])]
    (is (= 1 (count (:toasts result))))
    (is (= "t2" (:id (first (:toasts result)))))))

;; --- History events ---

(deftest history-loaded
  (let [data [["sha1" "alice" "feat" "2024-01-01" "add feature" 10 2]]
        result (handle base-state [:action/history-loaded {:results data}])]
    (is (= data (:history/commits result)))
    (is (not (:history/loading? result)))))

(deftest history-refresh-emits-post
  (let [fx (effects base-state [:action/history-refresh])]
    (is (= :http/post (ffirst fx)))))
