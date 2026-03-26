(ns noumenon.agent
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.llm :as llm]
            [noumenon.query :as query]))

;; --- Query validation ---

(def ^:private allowed-predicates
  "Allowlist of safe predicates/functions permitted in Datalog predicate/function clauses."
  #{'< '> '<= '>= '= '!= 'not= 'ground 'get-else 'get-some 'missing?
    'tuple 'untuple 'count 'min 'max 'sum 'avg 'median 'distinct 'rand 'sample
    '+ '- '* '/ 'inc 'dec 'mod 'rem 'quot 'abs
    'str 'subs 'name 'namespace 'keyword 'symbol
    'even? 'odd? 'pos? 'neg? 'zero? 'nil? 'some? 'int? 'string? 'keyword?
    'not 'and 'or 'identity
    'clojure.string/starts-with? 'clojure.string/ends-with? 'clojure.string/includes?
    'clojure.string/lower-case 'clojure.string/upper-case 'clojure.string/trim})

(def ^:private banned-symbols
  "Symbols that must never appear anywhere in a Datalog query (fallback layer)."
  #{'eval 'read-string 'load-string 'intern 'resolve 'ns-resolve
    'alter-var-root 'slurp 'spit 'sh 'clojure.java.shell/sh
    'binding 'Thread 'System 'Runtime 'ProcessBuilder})

(defn- java-class-reference?
  "True if sym looks like a Java class (contains a dot but is not on the allowlist)."
  [sym]
  (and (symbol? sym)
       (not (allowed-predicates sym))
       (str/includes? (str sym) ".")))

(defn- predicate-clause?
  "True if form is a Datalog predicate/function clause: a vector wrapping a single list."
  [form]
  (and (vector? form) (= 1 (count form)) (list? (first form))))

(defn- extract-predicate-syms
  "Extract predicate/function call symbols from all clauses in a query form."
  [query-form]
  (->> (tree-seq coll? seq query-form)
       (filter predicate-clause?)
       (map (comp first first))
       (filter symbol?)))

(defn validate-query
  "Walk a Datalog query form and reject unsafe symbols. Uses an allowlist for
   predicate/function clauses and a deny-list + Java class check as fallback.
   Returns nil if safe, or an error string if unsafe."
  [query-form]
  (or (->> (tree-seq coll? seq query-form)
           (filter symbol?)
           (some #(when (or (banned-symbols %) (java-class-reference? %))
                    (str "Blocked symbol in query: " %))))
      (->> (extract-predicate-syms query-form)
           (some #(when-not (allowed-predicates %)
                    (str "Predicate not on allowlist: " %))))))

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

(defn- sanitize-repo-name
  "Strip template metacharacters from repo-name to prevent prompt injection."
  [repo-name]
  (str/replace repo-name #"[{}]" ""))

(defn build-system-prompt
  "Render the agent system prompt with live schema, rules, and examples."
  [db repo-name]
  (-> @prompt-template
      (str/replace "{{repo-name}}" (sanitize-repo-name repo-name))
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
  (let [q (:query parsed-args)]
    (if-let [err (validate-query q)]
      (str "Query rejected: " err)
      (try
        (let [limit  (or (:limit parsed-args) default-row-limit)
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
          (str "Query error: " (.getMessage e)))))))

(defn- dispatch-schema [db]
  (query/schema-summary db))

(defn- dispatch-rules []
  (pr-str (query/load-rules)))

(defn dispatch-tool
  "Dispatch a parsed tool call. Returns {:result string} or {:answer string}."
  [db tool-call]
  (let [handlers {:query  (fn [args] {:result (dispatch-query db args)})
                  :schema (fn [_] {:result (dispatch-schema db)})
                  :rules  (fn [_] {:result (dispatch-rules)})
                  :answer (fn [args] {:answer (:text args "")})}]
    (if-let [handle (handlers (:tool tool-call))]
      (handle (:args tool-call))
      {:result (str "Unknown tool: " (:tool tool-call)
                    ". Available: :query, :schema, :rules, :answer")})))

;; --- Agent loop ---

(def ^:private default-max-iterations 10)

(defn- parse-error-transition
  [messages response-text parse-error]
  (let [error-msg (str "Your response could not be parsed as EDN. Error: "
                       parse-error
                       "\nPlease respond with exactly one EDN map.")]
    (conj messages
          {:role "assistant" :content response-text}
          {:role "user" :content error-msg})))

(defn- tool-result-transition
  [messages response-text result]
  (conj messages
        {:role "assistant" :content response-text}
        {:role "user" :content (str "Tool result:\n" result)}))

(defn- next-state
  [{:keys [db invoke-fn system-prompt]}
   {:keys [messages steps iterations total-usage max-iterations]}]
  (if (>= iterations max-iterations)
    {:done {:answer (or (some :answer steps)
                        "Budget exhausted: reached maximum iterations without a final answer.")
            :steps  steps
            :usage  (assoc total-usage :iterations iterations)
            :status :budget-exhausted}}
    (let [full-messages (update-in (vec messages) [0 :content]
                                   #(str system-prompt "\n\n" %))
          response      (invoke-fn full-messages)
          usage         (merge-with + total-usage
                                    (select-keys (:usage response)
                                                 [:input-tokens :output-tokens :cost-usd :duration-ms]))
          parsed        (parse-response (:text response))
          step          {:iteration (inc iterations)
                         :raw-text (:text response)
                         :parsed parsed}]
      (if-let [parse-error (:parse-error parsed)]
        {:messages (parse-error-transition messages (:text response) parse-error)
         :steps (conj steps (assoc step :error parse-error))
         :iterations (inc iterations)
         :total-usage usage
         :max-iterations max-iterations}
        (let [{:keys [result answer]} (dispatch-tool db parsed)]
          (if answer
            {:done {:answer answer
                    :steps  (conj steps (assoc step :answer answer))
                    :usage  (assoc usage :iterations (inc iterations))
                    :status :answered}}
            {:messages (tool-result-transition messages (:text response) result)
             :steps (conj steps (assoc step :tool-result result))
             :iterations (inc iterations)
             :total-usage usage
             :max-iterations max-iterations}))))))

(defn ask
  "Run the agent loop: prompt → parse → dispatch → repeat.
   Returns {:answer string :steps vec :usage {:iterations n :input-tokens n :output-tokens n}}."
  [db question {:keys [invoke-fn repo-name max-iterations]
                :or   {max-iterations default-max-iterations}}]
  (let [system-prompt (build-system-prompt db repo-name)
        context      {:db db :invoke-fn invoke-fn :system-prompt system-prompt}
        initial      {:messages [{:role "user" :content question}]
                      :steps []
                      :iterations 0
                      :total-usage llm/zero-usage
                      :max-iterations max-iterations}]
    (loop [state initial]
      (let [nxt (next-state context state)]
        (if-let [done (:done nxt)]
          done
          (recur nxt))))))
