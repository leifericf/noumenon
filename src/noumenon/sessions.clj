(ns noumenon.sessions
  "Shared introspect session management for MCP and HTTP layers.")

(defonce sessions (atom {}))
;; {run-id {:status :running/:completed/:stopped/:error
;;          :future <future> :result <map> :stop-flag <atom<bool>>
;;          :started-at <epoch-ms> :completed-at <epoch-ms>}}

(def max-sessions 5)
(def ^:private session-ttl-ms (* 30 60 1000))

(defn evict-stale!
  "Remove completed/errored sessions older than session-ttl-ms."
  []
  (let [now (System/currentTimeMillis)]
    (swap! sessions
           (fn [ss]
             (into {}
                   (remove (fn [[_ v]]
                             (and (#{:completed :error :stopped} (:status v))
                                  (when-let [t (:completed-at v)]
                                    (> (- now t) session-ttl-ms)))))
                   ss)))))

(defn running-count []
  (->> @sessions vals (filter (comp #{:running} :status)) count))

(defn get-session [run-id]
  (get @sessions run-id))

(defn register! [run-id session-map]
  (swap! sessions assoc run-id session-map))

(defn update-session! [run-id f]
  (swap! sessions update run-id f))

(defn format-result-summary [result]
  (str "Improvements: " (:improvements result)
       " in " (:iterations result) " iterations"
       "\nFinal score: " (format "%.3f" (double (:final-score result 0)))))
