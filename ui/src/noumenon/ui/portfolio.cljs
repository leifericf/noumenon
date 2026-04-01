(ns noumenon.ui.portfolio
  (:require [replicant.dom :as r]
            [portfolio.ui :as ui]
            noumenon.ui.portfolio.scenes.button-scenes
            noumenon.ui.portfolio.scenes.progress-scenes
            noumenon.ui.portfolio.scenes.card-scenes
            noumenon.ui.portfolio.scenes.badge-scenes
            noumenon.ui.portfolio.scenes.input-scenes
            noumenon.ui.portfolio.scenes.sidebar-scenes
            noumenon.ui.portfolio.scenes.table-scenes
            noumenon.ui.portfolio.scenes.toast-scenes
            noumenon.ui.portfolio.scenes.skeleton-scenes
            noumenon.ui.portfolio.scenes.markdown-scenes))

(defn ^:export init []
  ;; Register a no-op dispatch so data-driven event handlers don't throw
  (r/set-dispatch! (fn [_ _]))
  (ui/start!
   {:config {:css-paths []}}))
