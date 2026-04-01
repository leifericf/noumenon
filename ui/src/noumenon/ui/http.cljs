(ns noumenon.ui.http)

;; Active backend connection — set by state.cljs when backend changes
(defonce connection
  (atom {:url   (str "http://localhost:"
                     (or (when (exists? js/window) js/window.__NOUMENON_PORT__) 9876))
         :token nil}))

(defn base-url [] (:url @connection))

(defn- auth-headers []
  (let [token (:token @connection)]
    (if (seq token)
      {"Accept" "application/json" "Authorization" (str "Bearer " token)}
      {"Accept" "application/json"})))

(defn- parse-json [text]
  (js->clj (js/JSON.parse text) :keywordize-keys true))

(defn- handle-response [resp {:keys [on-ok on-error]}]
  (if-not (.-ok resp)
    (-> (.text resp)
        (.then (fn [text]
                 (when on-error
                   (on-error (str "HTTP " (.-status resp) ": "
                                  (subs text 0 (min 200 (count text)))))))))
    (-> (.text resp)
        (.then (fn [text]
                 (let [body (parse-json text)]
                   (if (:ok body)
                     (when on-ok (on-ok (:data body)))
                     (when on-error (on-error (or (:error body) "Unknown error"))))))))))

(defn api-get!
  [path {:keys [on-ok on-error] :as cbs}]
  (-> (js/fetch (str (base-url) path)
                (clj->js {:headers (auth-headers)}))
      (.then #(handle-response % cbs))
      (.catch (fn [_]
                (when on-error (on-error "Connection error"))))))

(defn api-post!
  [path body {:keys [on-ok on-error] :as cbs}]
  (-> (js/fetch (str (base-url) path)
                (clj->js {:method "POST"
                          :headers (merge (auth-headers)
                                          {"Content-Type" "application/json"})
                          :body (js/JSON.stringify (clj->js body))}))
      (.then #(handle-response % cbs))
      (.catch (fn [_]
                (when on-error (on-error "Connection error"))))))

(defn api-delete!
  [path {:keys [on-ok on-error] :as cbs}]
  (-> (js/fetch (str (base-url) path)
                (clj->js {:method "DELETE"
                          :headers (auth-headers)}))
      (.then #(handle-response % cbs))
      (.catch (fn [_]
                (when on-error (on-error "Connection error"))))))

(defn- parse-sse-block
  [block]
  (let [lines (.split block "\n")]
    (loop [ls (seq lines)
           event nil
           data nil]
      (if-not ls
        (when (and event data) {:event event :data data})
        (let [line (first ls)]
          (cond
            (.startsWith line "event: ")
            (recur (next ls) (subs line 7) data)
            (.startsWith line "data: ")
            (recur (next ls) event (subs line 6))
            :else
            (recur (next ls) event data)))))))

(defn sse-post!
  "POST with SSE streaming. Returns a cancel function."
  [path body {:keys [on-progress on-result on-done on-error]}]
  (let [controller (js/AbortController.)
        done?      (atom false)]
    (-> (js/fetch (str (base-url) path)
                  (clj->js {:method "POST"
                            :headers (merge (auth-headers)
                                            {"Content-Type" "application/json"
                                             "Accept" "text/event-stream"})
                            :body (js/JSON.stringify (clj->js body))
                            :signal (.-signal controller)}))
        (.then
         (fn [resp]
           (let [reader  (.getReader (.-body resp))
                 decoder (js/TextDecoder.)
                 buffer  (atom "")
                 fire-done! (fn []
                              (when (compare-and-set! done? false true)
                                (when on-done (on-done))))]
             (letfn [(process-buffer! []
                       (let [text @buffer
                             parts (.split text "\n\n")]
                         (when (> (count parts) 1)
                           (doseq [block (butlast parts)]
                             (when-let [{:keys [event data]} (parse-sse-block block)]
                               (let [parsed (parse-json data)]
                                 (case event
                                   "progress" (when on-progress (on-progress parsed))
                                   "result"   (when on-result (on-result parsed))
                                   "error"    (when on-error (on-error (:error parsed)))
                                   "done"     (fire-done!)
                                   nil))))
                           (reset! buffer (last parts)))))
                     (read-chunk []
                       (-> (.read reader)
                           (.then
                            (fn [result]
                              (if (.-done result)
                                (do (process-buffer!)
                                    (fire-done!))
                                (do (swap! buffer str (.decode decoder (.-value result)))
                                    (process-buffer!)
                                    (read-chunk)))))))]
               (read-chunk)))))
        (.catch (fn [_]
                  (when-not (.-aborted (.-signal controller))
                    (when on-error (on-error "Connection error"))))))
    (fn [] (.abort controller))))
