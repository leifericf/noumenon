(ns noumenon.ui.portfolio.scenes.toast-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.toast :as toast]))

(portfolio/defscene error-toast
  (toast/toast {:message "Connection refused" :type :error}))

(portfolio/defscene success-toast
  (toast/toast {:message "Import complete" :type :success}))

(portfolio/defscene warning-toast
  (toast/toast {:message "Rate limit approaching" :type :warning}))

(portfolio/defscene multiple-toasts
  (toast/toast-container
   [{:id "1" :message "Import complete" :type :success}
    {:id "2" :message "Benchmark failed" :type :error}]))
