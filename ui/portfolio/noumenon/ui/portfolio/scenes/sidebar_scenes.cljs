(ns noumenon.ui.portfolio.scenes.sidebar-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.sidebar :as sidebar]))

(portfolio/defscene sidebar-expanded
  (sidebar/sidebar {:active-route :ask :collapsed? false :theme :dark}))

(portfolio/defscene sidebar-collapsed
  (sidebar/sidebar {:active-route :graph :collapsed? true :theme :dark}))

(portfolio/defscene sidebar-light-theme
  (sidebar/sidebar {:active-route :databases :collapsed? false :theme :light}))
