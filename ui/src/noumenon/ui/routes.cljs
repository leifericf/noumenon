(ns noumenon.ui.routes
  (:require [noumenon.ui.state :as state]))

(def routes
  {"#/ask"        :ask
   "#/databases"  :databases
   "#/graph"      :graph
   "#/schema"     :schema
   "#/benchmark"  :benchmark
   "#/introspect" :introspect
   "#/history"    :history})

(defn current-route []
  (get routes (.-hash js/location) :ask))

(defn- on-route-change [route]
  (state/dispatch! [:action/route-changed route])
  (case route
    :ask       (state/dispatch! [:action/ask-load-history])
    :schema    (do (state/dispatch! [:action/schema-load])
                   (state/dispatch! [:action/query-history-load]))
    :graph     (state/dispatch! [:action/graph-load])
    :benchmark (state/dispatch! [:action/bench-refresh])
    :introspect (state/dispatch! [:action/introspect-refresh])
    :history   (state/dispatch! [:action/history-refresh])
    nil))

(defn init! []
  (.addEventListener js/window "hashchange"
                     (fn [_] (on-route-change (current-route))))
  (on-route-change (current-route)))
