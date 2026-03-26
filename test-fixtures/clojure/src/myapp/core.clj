(ns myapp.core
  (:require [myapp.db :as db]
            [myapp.util :as util]))

(defn start [] (db/connect) (util/log "started"))
