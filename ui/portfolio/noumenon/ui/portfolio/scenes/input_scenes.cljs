(ns noumenon.ui.portfolio.scenes.input-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.input :as input]))

(portfolio/defscene text-input
  (input/text-input {:placeholder "Enter text..." :value ""}))

(portfolio/defscene text-input-with-value
  (input/text-input {:placeholder "Enter text..." :value "hello world"}))

(portfolio/defscene search-bar
  (input/search-bar {:placeholder "Ask about your codebase..."
                     :value ""
                     :on-input [:noop]
                     :on-submit [:noop]}))

(portfolio/defscene search-bar-loading
  (input/search-bar {:placeholder "Ask..."
                     :value "what files are coupled?"
                     :loading? true
                     :on-input [:noop]
                     :on-submit [:noop]}))

(portfolio/defscene textarea-empty
  (input/textarea {:placeholder "[:find ?path\n :where [?f :file/path ?path]]"}))
