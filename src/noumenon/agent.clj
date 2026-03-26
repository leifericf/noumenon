(ns noumenon.agent
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.query :as query]))

;; --- Prompt assembly ---

(def ^:private prompt-template
  (delay (-> (query/load-edn-resource "prompts/agent-system.edn") :template)))

(defn- format-examples
  "Format named queries as example Datalog for the system prompt."
  []
  (->> (query/list-query-names)
       (keep query/load-named-query)
       (map (fn [{:keys [name description query uses-rules]}]
              (str ";;; " name " — " description "\n"
                   (pr-str query)
                   (when uses-rules "\n;; (uses rules — pass % as second input)"))))
       (str/join "\n\n")))

(defn build-system-prompt
  "Render the agent system prompt with live schema, rules, and examples."
  [db repo-name]
  (-> @prompt-template
      (str/replace "{{repo-name}}" repo-name)
      (str/replace "{{schema}}" (query/schema-summary db))
      (str/replace "{{rules}}" (pr-str (query/load-rules)))
      (str/replace "{{examples}}" (format-examples))))

;; --- Response parsing ---

(defn parse-response
  "Parse an LLM response string into an EDN tool call.
   Strips markdown fences if present. Returns the parsed map or
   {:parse-error string} if unparseable."
  [text]
  (let [cleaned (analyze/strip-markdown-fences text)]
    (try
      (let [parsed (edn/read-string cleaned)]
        (if (map? parsed)
          parsed
          {:parse-error (str "Expected EDN map, got: " (type parsed))}))
      (catch Exception e
        {:parse-error (str "EDN parse error: " (.getMessage e) "\nRaw text: " text)}))))

;; --- Tool dispatch ---

(def ^:private default-row-limit 100)

(defn- dispatch-query
  "Execute a Datalog query against db. Returns result text with optional truncation note."
  [db parsed-args]
  (try
    (let [q      (:query parsed-args)
          limit  (or (:limit parsed-args) default-row-limit)
          rules  (query/load-rules)
          ;; Try with rules first; if the query doesn't use :in, it won't need them
          result (try
                   (d/q q db rules)
                   (catch Exception _
                     (d/q q db)))
          total  (count result)
          capped (take limit result)]
      (str (pr-str (vec capped))
           (when (> total limit)
             (str "\n;; Showing " limit " of " total " results. Refine your query or specify :limit."))))
    (catch Exception e
      (str "Query error: " (.getMessage e)))))

(defn- dispatch-schema [db]
  (query/schema-summary db))

(defn- dispatch-rules []
  (pr-str (query/load-rules)))

(defn dispatch-tool
  "Dispatch a parsed tool call. Returns {:result string} or {:answer string}."
  [db tool-call]
  (case (:tool tool-call)
    :query  {:result (dispatch-query db (:args tool-call))}
    :schema {:result (dispatch-schema db)}
    :rules  {:result (dispatch-rules)}
    :answer {:answer (get-in tool-call [:args :text] "")}
    {:result (str "Unknown tool: " (:tool tool-call)
                  ". Available: :query, :schema, :rules, :answer")}))

;; --- Agent loop ---

(def ^:private default-max-iterations 10)

(defn ask
  "Run the agent loop: prompt → parse → dispatch → repeat.
   Returns {:answer string :steps vec :usage {:iterations n :input-tokens n :output-tokens n}}."
  [db question {:keys [invoke-fn repo-name max-iterations]
                :or   {max-iterations default-max-iterations}}]
  (let [system-prompt (build-system-prompt db repo-name)
        initial-msgs  [{:role "user" :content question}]]
    (loop [messages    initial-msgs
           steps       []
           iterations  0
           total-usage {:input-tokens 0 :output-tokens 0}]
      (if (>= iterations max-iterations)
        {:answer (or (some :answer steps)
                     "Budget exhausted: reached maximum iterations without a final answer.")
         :steps  steps
         :usage  (assoc total-usage :iterations iterations)
         :status :budget-exhausted}
        (let [full-messages (into [{:role "user" :content system-prompt}] messages)
              response      (invoke-fn full-messages)
              usage         (merge-with + total-usage
                                        (select-keys (:usage response) [:input-tokens :output-tokens]))
              parsed        (parse-response (:text response))
              step          {:iteration  (inc iterations)
                             :raw-text   (:text response)
                             :parsed     parsed}]
          (if (:parse-error parsed)
            ;; Feed parse error back to agent
            (let [error-msg (str "Your response could not be parsed as EDN. Error: "
                                 (:parse-error parsed)
                                 "\nPlease respond with exactly one EDN map.")]
              (recur (conj messages
                           {:role "assistant" :content (:text response)}
                           {:role "user" :content error-msg})
                     (conj steps (assoc step :error (:parse-error parsed)))
                     (inc iterations)
                     usage))
            (let [{:keys [result answer]} (dispatch-tool db parsed)]
              (if answer
                ;; Agent emitted final answer
                {:answer answer
                 :steps  (conj steps (assoc step :answer answer))
                 :usage  (assoc usage :iterations (inc iterations))
                 :status :answered}
                ;; Feed tool result back
                (recur (conj messages
                             {:role "assistant" :content (:text response)}
                             {:role "user" :content (str "Tool result:\n" result)})
                       (conj steps (assoc step :tool-result result))
                       (inc iterations)
                       usage)))))))))
