(ns noumenon.cli.commands.ask
  "CLI command handler for the LLM-driven `ask` agent."
  (:require [noumenon.agent :as agent]
            [noumenon.ask-store :as ask-store]
            [noumenon.cli.util :as cu]
            [noumenon.embed :as embed]
            [noumenon.llm :as llm]
            [noumenon.util :refer [log!]]))

(defn- log-verbose-steps
  "Log each step of the ask agent's reasoning."
  [result max-iterations]
  (let [max-iters (or max-iterations 10)]
    (doseq [step (:steps result)]
      (let [i   (:iteration step)
            tag (cond
                  (:answer step)      "answer"
                  (:error step)       (str "error: " (:error step))
                  (:tool-result step) (str "tool: " (or (some-> (:parsed step) :tool name) "unknown")
                                           " (" (count (:tool-result step)) " chars)")
                  :else               "thinking")]
        (log! (str "  [" i "/" max-iters "] " tag))))))

(defn- format-ask-result
  "Format an ask agent result into {:exit n :result map}."
  [result]
  (let [exhausted? (= :budget-exhausted (:status result))
        answer     (:answer result)
        session-id (:session-id result)]
    (if exhausted?
      (do (if answer
            (do (log! answer)
                (log! (str "\n[Session " session-id " saved — re-run with"
                           " --continue-from " session-id " to resume]")))
            (log! "Budget exhausted — no answer found."))
          {:exit   2
           :result (cond-> {:status :budget-exhausted :usage (:usage result)}
                     answer     (assoc :answer answer)
                     session-id (assoc :session-id session-id))})
      (do (when answer (log! answer))
          {:exit   0
           :result {:answer answer :status (:status result) :usage (:usage result)}}))))

(def ^:private max-ask-iterations
  "Upper bound shared with the HTTP /api/ask handler — caps the agent
   loop at 50 regardless of caller-supplied --max-iterations so a typo
   can't blow through cost budgets."
  50)

(def ^:private default-ask-iterations 10)

(defn- clamp-iterations
  "Clamp to [1, max-ask-iterations]. Missing → default-ask-iterations."
  [n]
  (-> (or n default-ask-iterations) (max 1) (min max-ask-iterations)))

(defn- save-session-best-effort!
  "Persist the ask session to the meta DB. Mirrors the HTTP handler's
   save shape so introspect-loop queries see CLI runs alongside HTTP/MCP
   ones. Failures are logged and swallowed — a save error must not
   block returning the answer to the user."
  [meta-conn result {:keys [question db-name started-at duration-ms]}]
  (try
    (ask-store/save-session!
     meta-conn result
     {:channel     :cli
      :caller      :human
      :repo        db-name
      :question    question
      :started-at  started-at
      :duration-ms duration-ms})
    (catch Exception e
      (log! "ask-store/error" (.getMessage e))
      nil)))

(defn do-ask
  "Run the ask subcommand."
  [{:keys [question model provider max-iterations continue-from verbose] :as opts}]
  (cu/with-valid-repo
    opts
    (fn [ctx]
      (try
        (cu/with-existing-db
          ctx
          (fn [{:keys [db meta-db meta-conn db-name]}]
            (let [{:keys [invoke-fn]}
                  (llm/make-messages-fn-from-opts {:provider    provider
                                                   :model       model
                                                   :temperature 0.3
                                                   :max-tokens  4096})
                  eidx       (embed/get-cached-index (:db-dir ctx) db-name)
                  max-iter   (clamp-iterations max-iterations)
                  started-at (java.util.Date.)
                  start-ms   (System/currentTimeMillis)
                  result     (agent/ask meta-db db question
                                        (cond-> {:invoke-fn invoke-fn :repo-name db-name
                                                 :embed-index eidx
                                                 :max-iterations max-iter}
                                          continue-from (assoc :continue-from continue-from)))
                  duration   (- (System/currentTimeMillis) start-ms)
                  session-id (save-session-best-effort!
                              meta-conn result
                              {:question    question
                               :db-name     db-name
                               :started-at  started-at
                               :duration-ms duration})
                  result*    (cond-> result session-id (assoc :session-id session-id))]
              (when verbose (log-verbose-steps result* max-iter))
              (format-ask-result result*))))
        (catch clojure.lang.ExceptionInfo e
          (cu/print-error! (.getMessage e))
          {:exit 1})))))
