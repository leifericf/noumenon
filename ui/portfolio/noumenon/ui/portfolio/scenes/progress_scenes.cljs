(ns noumenon.ui.portfolio.scenes.progress-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.progress :as progress]))

(portfolio/defscene progress-0
  (progress/bar {:current 0 :total 100 :message "Starting..."}))

(portfolio/defscene progress-50
  (progress/bar {:current 50 :total 100 :message "Analyzing files..."}))

(portfolio/defscene progress-100
  (progress/bar {:current 100 :total 100 :message "Complete"}))
