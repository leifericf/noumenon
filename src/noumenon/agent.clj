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
  "True if sym looks like a Java class reference. Catches both dotted forms
   (java.lang.Runtime) and static method calls (Runtime/getRuntime)."
  [sym]
  (and (symbol? sym)
       (not (allowed-predicates sym))
       (or (str/includes? (str sym) ".")
           (when-let [ns (namespace sym)]
             (Character/isUpperCase (first ns))))))

(defn- dot-interop-form?
  "True if form is a dot-interop list like (. Foo bar) or (.. Foo bar baz),
   or contains a symbol starting with '.' (e.g. .method)."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (let [head (str (first form))]
         (or (= "." head)
             (= ".." head)
             (str/starts-with? head ".")))))

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
  (let [forms (tree-seq coll? seq query-form)]
    (or (some #(when (dot-interop-form? %)
                 (str "Blocked dot-interop in query: " (first %)))
              forms)
        (let [banned-strs (into #{} (map str) banned-symbols)]
          (->> forms
               (some #(cond
                        (and (symbol? %) (or (banned-symbols %) (java-class-reference? %)))
                        (str "Blocked symbol in query: " %)
                        (and (keyword? %) (banned-strs (name %)))
                        (str "Blocked symbol in query: " %)
                        (and (string? %) (banned-strs %))
                        (str "Blocked symbol in query: " %)))))
        (->> (extract-predicate-syms query-form)
             (some #(when-not (allowed-predicates %)
                      (str "Predicate not on allowlist: " %)))))))

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
  "Allowlist-sanitize repo-name to prevent prompt injection via the system prompt."
  [repo-name]
  (str/replace repo-name #"[^a-zA-Z0-9\-_.]" ""))

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
(def ^:private max-row-limit 1000)

(def ^:private query-timeout-ms
  "Maximum time allowed for a single Datalog query (30 seconds)."
  30000)

(defn- extract-find-vars
  "Extract variable names from a Datalog :find clause for column headers."
  [query-form]
  (when (sequential? query-form)
    (let [find-idx (.indexOf (vec query-form) :find)]
      (when (>= find-idx 0)
        (->> (drop (inc find-idx) query-form)
             (take-while #(not (keyword? %)))
             (map pr-str))))))

(defn- format-column-header [query-form]
  (when-let [vars (seq (extract-find-vars query-form))]
    (str ";; Columns: " (str/join ", " vars) "\n")))

(defn- dispatch-query
  "Execute a Datalog query against db. Returns result text with optional truncation note."
  [db parsed-args]
  (let [q (:query parsed-args)]
    (if-let [err (validate-query q)]
      (str "Query rejected: " err)
      (try
        (let [limit  (min (or (:limit parsed-args) default-row-limit) max-row-limit)
              rules  (query/load-rules)
              f      (future (try
                               (d/q q db rules)
                               (catch Exception e
                                 (if (str/includes? (str (.getMessage e)) "arity")
                                   (d/q q db)
                                   (throw e)))
                               (catch OutOfMemoryError _
                                 ::oom)))
              result (deref f query-timeout-ms ::timeout)]
          (condp = result
            ::timeout (do (future-cancel f)
                          "Query timed out after 30 seconds. Simplify the query or add more constraints.")
            ::oom     "Query exhausted available memory. Simplify the query or add more constraints."
            (let [taken  (vec (take (inc limit) result))
                  capped (take limit taken)]
              (str (format-column-header q)
                   (pr-str (vec capped))
                   (when (> (count taken) limit)
                     (str "\n;; Showing " limit " of " limit "+ results. Refine your query or specify :limit."))))))
        (catch Exception e
          (let [msg (.getMessage e)]
            (str "Query error: " msg
                 (cond
                   (str/includes? (str msg) "arity")
                   "\nHint: check that the number of :in bindings matches the inputs provided."
                   (str/includes? (str msg) "Could not find")
                   "\nHint: attribute may be misspelled. Use {:tool :schema} to verify."
                   :else
                   "\nHint: simplify the query or use {:tool :schema} to check attribute names."))))))))

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

;; --- Session storage (for resumable budget-exhausted runs) ---

(def ^:private max-sessions 20)
(def ^:private session-ttl-ms (* 30 60 1000)) ;; 30 minutes

(def ^:private sessions (atom {}))

(defn- evict-expired-sessions! []
  (let [cutoff (- (System/currentTimeMillis) session-ttl-ms)]
    (swap! sessions (fn [m]
                      (let [live (into {} (remove #(< (:created-at (val %)) cutoff)) m)]
                        (if (<= (count live) max-sessions)
                          live
                          (->> live
                               (sort-by (comp :created-at val))
                               (drop (- (count live) max-sessions))
                               (into {}))))))))

(defn- store-session! [state]
  (evict-expired-sessions!)
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! sessions assoc id (assoc state :created-at (System/currentTimeMillis)))
    id))

(defn- load-session! [id]
  (let [s (get @sessions id)]
    (when s
      (swap! sessions dissoc id)
      (dissoc s :created-at))))

;; --- Agent loop ---

(def ^:private default-max-iterations 10)

(def ^:private budget-nudge
  (str "You have reached your iteration budget. "
       "You MUST respond with {:tool :answer :args {:text \"...\"}} NOW. "
       "Synthesize the best answer you can from the information gathered so far. "
       "If your answer is incomplete, state what is missing in one sentence. "
       "Do not describe hypothetical queries or what you would do with more budget."))

(defn- iteration-prefix [iterations max-iterations]
  (str "[Iteration " (inc iterations) "/" max-iterations "] "))

(defn- parse-error-transition
  [messages response-text parse-error iterations max-iterations]
  (let [error-msg (str (iteration-prefix iterations max-iterations)
                       "Parse error: " parse-error
                       "\nRespond with exactly one EDN map, e.g.: "
                       "{:tool :query :args {:query [:find ?e :where [?e :file/path]]}}")]
    (conj messages
          {:role "assistant" :content response-text}
          {:role "user" :content error-msg})))

(defn- tool-result-transition
  [messages response-text result iterations max-iterations]
  (conj messages
        {:role "assistant" :content response-text}
        {:role "user" :content (str (iteration-prefix iterations max-iterations)
                                    "Tool result:\n" result)}))

(def ^:private early-warning
  "You have 3 iterations remaining. Start synthesizing your answer soon.")

(defn- maybe-append-nudge
  "Append early warning or hard budget nudge as iterations wind down."
  [messages iterations max-iterations]
  (let [remaining (- max-iterations iterations)]
    (cond
      (<= remaining 1) (conj messages {:role "user" :content budget-nudge})
      (= remaining 3)  (conj messages {:role "user" :content early-warning})
      :else            messages)))

(defn- next-state
  [{:keys [db invoke-fn]}
   {:keys [messages steps iterations total-usage max-iterations]}]
  (if (>= iterations max-iterations)
    (let [state {:messages messages :steps steps
                 :iterations iterations :total-usage total-usage
                 :max-iterations max-iterations}
          session-id (store-session! state)]
      {:done {:answer     (or (some :answer steps)
                              "Budget exhausted: reached maximum iterations without a final answer.")
              :steps      steps
              :usage      (assoc total-usage :iterations iterations)
              :status     :budget-exhausted
              :session-id session-id}})
    (let [msgs          (maybe-append-nudge messages iterations max-iterations)
          response      (invoke-fn (vec msgs))
          usage         (merge-with + total-usage
                                    (select-keys (:usage response)
                                                 [:input-tokens :output-tokens :cost-usd :duration-ms]))
          parsed        (parse-response (:text response))
          step          {:iteration (inc iterations)
                         :raw-text (:text response)
                         :parsed parsed}]
      (if-let [parse-error (:parse-error parsed)]
        {:messages (parse-error-transition messages (:text response) parse-error
                                           iterations max-iterations)
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
            {:messages (tool-result-transition messages (:text response) result
                                               iterations max-iterations)
             :steps (conj steps (assoc step :tool-result result))
             :iterations (inc iterations)
             :total-usage usage
             :max-iterations max-iterations}))))))

(defn ask
  "Run the agent loop: prompt → parse → dispatch → repeat.
   Returns {:answer string :steps vec :usage {:iterations n :input-tokens n :output-tokens n}
            :status :answered|:budget-exhausted :session-id string?}.
   Pass :continue-from session-id to resume a budget-exhausted session."
  [db question {:keys [invoke-fn repo-name max-iterations continue-from]
                :or   {max-iterations default-max-iterations}}]
  (let [context {:db db :invoke-fn invoke-fn}
        initial (if-let [prev (when continue-from (load-session! continue-from))]
                  (assoc prev :max-iterations (+ (:iterations prev) max-iterations))
                  {:messages [{:role "user" :content (str (build-system-prompt db repo-name)
                                                          "\n\n" question)}]
                   :steps []
                   :iterations 0
                   :total-usage llm/zero-usage
                   :max-iterations max-iterations})]
    (loop [state initial]
      (let [nxt (next-state context state)]
        (if-let [done (:done nxt)]
          done
          (recur nxt))))))
