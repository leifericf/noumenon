(ns noumenon.ui.portfolio.scenes.skeleton-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.skeleton :as skeleton]))

(portfolio/defscene single-bar
  (skeleton/bar {:width "200px" :height "16px"}))

(portfolio/defscene skeleton-rows
  (skeleton/rows {:count 5 :height "24px" :gap "8px"}))

(portfolio/defscene wide-skeleton
  (skeleton/rows {:count 3 :height "40px" :gap "12px"}))
