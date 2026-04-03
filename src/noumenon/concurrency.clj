(ns noumenon.concurrency
  "Server-wide concurrency controls for shared deployments.")

;; --- Global LLM semaphore ---

(defonce ^:private llm-semaphore (atom nil))

(defn init-llm-semaphore!
  "Initialize the global LLM concurrency semaphore."
  [max-permits]
  (reset! llm-semaphore (java.util.concurrent.Semaphore. max-permits true)))

(defn acquire-llm-permit!
  "Try to acquire an LLM permit. Returns true if acquired, false if unavailable."
  []
  (when-let [^java.util.concurrent.Semaphore sem @llm-semaphore]
    (.tryAcquire sem)))

(defn release-llm-permit!
  "Release an LLM permit."
  []
  (when-let [^java.util.concurrent.Semaphore sem @llm-semaphore]
    (.release sem)))

(defmacro with-llm-permit
  "Execute body with an LLM permit. Throws if no permit available."
  [& body]
  `(if (acquire-llm-permit!)
     (try ~@body (finally (release-llm-permit!)))
     (throw (ex-info "LLM concurrency limit reached"
                     {:status 429 :message "Too many concurrent LLM operations. Try again later."}))))

(defn llm-available-permits
  "Number of currently available LLM permits."
  []
  (when-let [^java.util.concurrent.Semaphore sem @llm-semaphore]
    (.availablePermits sem)))

;; --- Per-token ask session tracking ---

(defonce ^:private ask-sessions (atom {}))
;; {token-hash -> #{session-id ...}}

(def ^:private max-per-token 3)

(defn register-ask-session!
  "Register an ask session for a token. Returns session-id or throws if at limit."
  [token-hash session-id]
  (let [current (get @ask-sessions token-hash #{})]
    (when (>= (count current) max-per-token)
      (throw (ex-info (str "Maximum " max-per-token " concurrent ask sessions per token")
                      {:status 429
                       :message (str "Maximum " max-per-token " concurrent ask sessions. "
                                     "Wait for a session to complete.")})))
    (swap! ask-sessions update token-hash (fnil conj #{}) session-id)
    session-id))

(defn unregister-ask-session!
  "Remove an ask session for a token."
  [token-hash session-id]
  (swap! ask-sessions update token-hash disj session-id))

(defn active-ask-count
  "Total active ask sessions across all tokens."
  []
  (apply + (map count (vals @ask-sessions))))
