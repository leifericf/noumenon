(ns noumenon.ui.portfolio.scenes.button-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.button :as button]))

(portfolio/defscene primary-button
  (button/primary {:on {:click [:noop]}} "Import"))

(portfolio/defscene secondary-button
  (button/secondary {:on {:click [:noop]}} "Cancel"))

(portfolio/defscene danger-button
  (button/danger {:on {:click [:noop]}} "Delete"))

(portfolio/defscene small-button
  (button/small {:on {:click [:noop]}} "Enrich"))

(portfolio/defscene disabled-button
  (button/primary {:on {:click [:noop]} :disabled? true} "Disabled"))
