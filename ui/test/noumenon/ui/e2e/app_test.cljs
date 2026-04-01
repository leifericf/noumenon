(ns noumenon.ui.e2e.app-test
  (:require [cljs.test :refer [deftest is testing]]
            [noumenon.ui.state :as state]
            [noumenon.ui.views.shell :as shell]))

(deftest app-state-has-expected-keys
  (is (map? @state/app-state))
  (is (contains? @state/app-state :route))
  (is (contains? @state/app-state :databases/list))
  (is (contains? @state/app-state :toasts)))

(deftest initial-route-is-ask
  (is (= :ask (:route @state/app-state))))

(deftest shell-produces-hiccup
  (let [hiccup (shell/app-shell @state/app-state)]
    (is (vector? hiccup))
    (is (= :div (first hiccup)))))

(deftest all-routes-render-without-error
  (doseq [route [:ask :databases :graph :schema :benchmark :history]]
    (let [state  (assoc @state/app-state :route route)
          hiccup (shell/app-shell state)]
      (is (vector? hiccup) (str "Route " route " should produce hiccup")))))
