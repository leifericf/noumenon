(ns noumenon.ui.portfolio.scenes.table-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.table :as table]))

(portfolio/defscene basic-table
  (table/data-table
   {:columns [{:key :name :label "Name"}
              {:key :commits :label "Commits"}
              {:key :files :label "Files"}]
    :rows [{:name "noumenon" :commits 351 :files 198}
           {:name "ring" :commits 1200 :files 45}
           {:name "clojure" :commits 8000 :files 500}]}))
