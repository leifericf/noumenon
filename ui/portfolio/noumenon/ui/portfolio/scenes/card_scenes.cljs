(ns noumenon.ui.portfolio.scenes.card-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.card :as card]))

(portfolio/defscene basic-card
  (card/card {}
             [:p "A simple card with some content."]))

(portfolio/defscene card-with-header
  (card/card {}
             (card/card-header "Database Status")
             [:p {:style {:color "#8b949e"}} "351 commits, 198 files, 63 directories"]))
