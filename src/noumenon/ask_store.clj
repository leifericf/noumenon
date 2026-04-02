(ns noumenon.ask-store
  "Persist ask sessions and steps to Datomic for analytics,
   introspection, and conversation history."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]))

(defn- extract-reasoning
  "Extract the LLM reasoning text before the EDN tool call."
  [raw-text]
  (when raw-text
    (let [trimmed (str/trim raw-text)
          idx     (min (or (str/index-of trimmed "{") 9999)
                       (or (str/index-of trimmed "[") 9999))]
      (when (and (pos? idx) (< idx 9999))
        (let [text (str/trim (subs trimmed 0 idx))]
          (when (seq text)
            (subs text 0 (min 4000 (count text)))))))))

(defn- extract-query-edn
  "Extract the Datalog query from parsed tool args."
  [parsed]
  (when (and (sequential? parsed) (= 1 (count parsed)))
    (let [{:keys [tool args]} (first parsed)]
      (when (= :query tool)
        (pr-str (:query args))))))

(defn- extract-step-type [step]
  (cond
    (:error step)  :error
    (:answer step) :answer
    :else (let [parsed (:parsed step)]
            (when (sequential? parsed)
              (some-> parsed first :tool)))))

(defn- result-line-count [result-text]
  (when result-text
    (count (str/split-lines result-text))))

(defn- result-sample [result-text]
  (when (and result-text (seq result-text))
    (subs result-text 0 (min 4000 (count result-text)))))

(defn build-step-tx
  "Build a Datomic transaction map for a single step. Pure."
  [step idx elapsed-ms]
  (let [step-type  (extract-step-type step)
        query-edn  (extract-query-edn (:parsed step))
        reasoning  (extract-reasoning (:raw-text step))
        result-txt (:tool-result step)]
    (cond-> {:ask.step/ordinal idx
             :ask.step/type    (or step-type :unknown)}
      query-edn  (assoc :ask.step/query-edn query-edn)
      result-txt (assoc :ask.step/result-count (or (result-line-count result-txt) 0)
                        :ask.step/result-sample (result-sample result-txt))
      reasoning  (assoc :ask.step/reasoning reasoning)
      elapsed-ms (assoc :ask.step/elapsed-ms elapsed-ms))))

(defn- build-session-tx
  "Build the Datomic transaction map for an ask session. Pure."
  [session-id result {:keys [channel caller repo question started-at duration-ms]}]
  (let [steps      (:steps result)
        usage      (:usage result)
        reflection (or (:reflection result)
                       (some :reflection (reverse steps)))
        step-txs   (mapv (fn [step idx]
                           (build-step-tx step idx (:elapsed-ms step)))
                         steps (range))]
    (cond-> {:ask.session/id         session-id
             :ask.session/question   question
             :ask.session/status     (or (:status result) :error)
             :ask.session/channel    (or channel :unknown)
             :ask.session/caller     (or caller :human)
             :ask.session/started-at (or started-at (java.util.Date.))
             :ask.session/steps      step-txs}
      (:answer result)
      (assoc :ask.session/answer (:answer result))

      repo
      (assoc :ask.session/repo repo)

      duration-ms
      (assoc :ask.session/duration-ms duration-ms)

      (:input-tokens usage)
      (assoc :ask.session/input-tokens (:input-tokens usage))

      (:output-tokens usage)
      (assoc :ask.session/output-tokens (:output-tokens usage))

      (:iterations usage)
      (assoc :ask.session/iterations (:iterations usage))

      (seq (:missing-attributes reflection))
      (assoc :ask.session/missing-attributes
             (pr-str (:missing-attributes reflection)))

      (seq (:quality-issues reflection))
      (assoc :ask.session/quality-issues
             (pr-str (:quality-issues reflection)))

      (seq (:suggested-queries reflection))
      (assoc :ask.session/suggested-queries
             (pr-str (:suggested-queries reflection)))

      (seq (:notes reflection))
      (assoc :ask.session/agent-notes (:notes reflection))

      (seq (:seed-results result))
      (assoc :ask.session/seed-results (pr-str (:seed-results result))))))

(defn save-session!
  "Persist a completed ask session to Datomic.
   result is the return value of agent/ask.
   opts: {:channel :ui/:cli/:mcp, :caller :human/:ai-agent,
          :repo db-name, :question string, :started-at inst, :duration-ms long}"
  [meta-conn result opts]
  (let [session-id (str (java.util.UUID/randomUUID))]
    (d/transact meta-conn {:tx-data [(build-session-tx session-id result opts)]})
    session-id))

(defn set-feedback!
  "Store user feedback comment on a session."
  [meta-conn session-id comment]
  (d/transact meta-conn
              {:tx-data [{:ask.session/id              session-id
                          :ask.session/feedback         :negative
                          :ask.session/feedback-comment (or comment "")}]}))

(defn list-sessions
  "List recent ask sessions, newest first."
  [meta-db & {:keys [limit] :or {limit 50}}]
  (->> (d/q '[:find ?id ?q ?status ?ch ?caller ?at ?dur ?iters
              :where
              [?e :ask.session/id ?id]
              [?e :ask.session/question ?q]
              [?e :ask.session/status ?status]
              [?e :ask.session/started-at ?at]
              [(get-else $ ?e :ask.session/channel :unknown) ?ch]
              [(get-else $ ?e :ask.session/caller :human) ?caller]
              [(get-else $ ?e :ask.session/duration-ms 0) ?dur]
              [(get-else $ ?e :ask.session/iterations 0) ?iters]]
            meta-db)
       (sort-by #(nth % 5) #(compare %2 %1))
       (take limit)
       (mapv (fn [[id q status ch caller at dur iters]]
               {:id id :question q :status status :channel ch
                :caller caller :started-at at :duration-ms dur
                :iterations iters}))))

(defn get-session
  "Get a full session with steps by ID."
  [meta-db session-id]
  (when-let [e (ffirst (d/q '[:find ?e :in $ ?id
                              :where [?e :ask.session/id ?id]]
                            meta-db session-id))]
    (let [session (d/pull meta-db '[*] e)]
      (update session :ask.session/steps
              (fn [steps]
                (->> steps
                     (map #(d/pull meta-db '[*] (:db/id %)))
                     (sort-by :ask.step/ordinal)))))))
