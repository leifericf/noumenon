(ns noumenon.ui.portfolio.scenes.badge-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.badge :as badge]))

(portfolio/defscene accent-badge
  (badge/badge {} "imported"))

(portfolio/defscene success-badge
  (badge/badge {:color "#3fb950"} "complete"))

(portfolio/defscene warning-badge
  (badge/badge {:color "#d29922"} "analyzing..."))

(portfolio/defscene danger-badge
  (badge/badge {:color "#f85149"} "error"))
