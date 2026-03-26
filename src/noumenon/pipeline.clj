(ns noumenon.pipeline
  (:require [clojure.core.async :as async]
            [noumenon.util :refer [log!]]))

(defn run-concurrent!
  "Run process-item! across items with bounded pipeline-blocking concurrency.
   Supports cooperative stop via stop-flag atom and exception capture via error-atom.
   Optional before-item! runs for each item before process-item! (e.g. rate gate).
   Returns stop reason keyword/string/nil from stop-flag.
   Rethrows first captured error after pipeline drain."
  [items {:keys [concurrency stop-flag error-atom before-item! process-item!]
          :or   {concurrency 1 stop-flag (atom nil) error-atom (atom nil)}}]
  (let [in-ch  (async/to-chan! (vec items))
        out-ch (async/chan (max 1 concurrency))]
    (async/pipeline-blocking
     concurrency
     out-ch
     (map (fn [item]
            (try
              (when-not @stop-flag
                (when before-item!
                  (before-item! item))
                (process-item! item))
              (catch Exception e
                (log! (str "pipeline/error: " (.getMessage e)))
                (when (compare-and-set! error-atom nil e)
                  (compare-and-set! stop-flag nil :error))))
            :done))
     in-ch)
    (loop []
      (when (async/<!! out-ch)
        (recur)))
    (when-let [e @error-atom]
      (throw e))
    @stop-flag))
