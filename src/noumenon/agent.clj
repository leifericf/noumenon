(ns noumenon.agent
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.llm :as llm]
            [noumenon.model :as model]
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

(defn- sanitize-repo-name
  "Allowlist-sanitize repo-name to prevent prompt injection via the system prompt."
  [repo-name]
  (str/replace repo-name #"[^a-zA-Z0-9\-_.]" ""))

(defn- format-examples
  "Build formatted example string from Datomic artifacts."
  [meta-db]
  (let [example-names (some-> (artifacts/load-prompt meta-db "agent-examples")
                              edn/read-string)]
    (->> example-names
         (keep (partial artifacts/load-named-query meta-db))
         (map (fn [{:keys [name description query uses-rules]}]
                (str ";;; " name " — " description "\n"
                     (pr-str query)
                     (when uses-rules "\n;; (uses rules — pass % as second input)"))))
         (str/join "\n\n"))))

(defn build-system-prompt
  "Render the agent system prompt with live schema, rules, and examples."
  [meta-db db repo-name]
  (-> (artifacts/load-prompt meta-db "agent-system")
      (str/replace "{{repo-name}}" (sanitize-repo-name repo-name))
      (str/replace "{{schema}}" (query/schema-summary db))
      (str/replace "{{rules}}" (pr-str (artifacts/load-rules meta-db)))
      (str/replace "{{examples}}" (format-examples meta-db))))

;; --- Response parsing ---

(def ^:private max-parallel-tools 5)

(defn parse-response
  "Parse an LLM response string into a vector of EDN tool calls.
   Accepts a single map or a vector of maps (for parallel execution).
   Strips markdown fences if present. Returns a vector of maps or
   {:parse-error string} if unparseable."
  [text]
  (let [cleaned (analyze/strip-markdown-fences text)]
    (try
      (let [parsed (edn/read-string cleaned)]
        (cond
          (map? parsed)
          [parsed]
          (and (vector? parsed) (seq parsed) (every? map? parsed))
          (vec (take max-parallel-tools parsed))
          :else
          {:parse-error (str "Expected EDN map or vector of maps, got: " (type parsed))}))
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

(defn- query-uses-rules?
  "Returns true if the Datalog query form references the rules variable `%`."
  [query-form]
  (->> (flatten (seq query-form))
       (some #{(symbol "%")})
       boolean))

(defn- dispatch-query
  "Execute a Datalog query against db. Returns result text with optional truncation note."
  [meta-db db parsed-args]
  (let [q (:query parsed-args)]
    (if-let [err (validate-query q)]
      (str "Query rejected: " err)
      (try
        (let [limit      (min (or (:limit parsed-args) default-row-limit) max-row-limit)
              rules      (artifacts/load-rules meta-db)
              uses-rules (query-uses-rules? q)
              _          (when (and uses-rules (nil? rules))
                           (throw (ex-info "Query references rules (%) but no rules are loaded. Seed rules first via noumenon_artifact_seed."
                                           {:query q})))
              f          (future (try
                                   (if rules
                                     (d/q q db rules)
                                     (d/q q db))
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

(defn- dispatch-rules [meta-db]
  (pr-str (artifacts/load-rules meta-db)))

(defn dispatch-tool
  "Dispatch a parsed tool call. Returns {:result string} or {:answer string}."
  [meta-db db tool-call]
  (let [handlers {:query   (fn [args] {:result (dispatch-query meta-db db args)})
                  :schema  (fn [_] {:result (dispatch-schema db)})
                  :rules   (fn [_] {:result (dispatch-rules meta-db)})
                  :answer  (fn [args] {:answer (:text args "")})
                  :reflect (fn [args] {:reflect args})}]
    (if-let [handle (handlers (:tool tool-call))]
      (handle (:args tool-call))
      {:result (str "Unknown tool: " (:tool tool-call)
                    ". Available: :query, :schema, :rules, :answer, :reflect")})))

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
       "First, emit a :reflect tool call with your observations about the data, then emit your :answer. "
       "Respond with a vector of two tool calls:\n"
       "[{:tool :reflect :args {:missing-attributes [\"...\"] :quality-issues [\"...\"] "
       ":suggested-queries [\"...\"] :notes \"...\"}}\n"
       " {:tool :answer :args {:text \"...\"}}]\n"
       "In :reflect, report: attributes you needed but couldn't find, data quality issues you observed, "
       "named queries that would have helped. If none, use empty vectors. "
       "In :answer, synthesize the best answer from what you gathered. "
       "If incomplete, state what is missing."))

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
  [{:keys [meta-db db invoke-fn system-prompt]}
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
          response      (invoke-fn (vec msgs) {:system system-prompt})
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
        ;; Check for :answer and :reflect tool calls
        (let [answer-call  (some #(when (= :answer (:tool %)) %) parsed)
              reflect-call (some #(when (= :reflect (:tool %)) %) parsed)
              reflection   (when reflect-call
                             (:args reflect-call))]
          (if answer-call
            (let [{:keys [answer]} (dispatch-tool meta-db db answer-call)]
              {:done {:answer     answer
                      :reflection reflection
                      :steps      (conj steps (cond-> (assoc step :answer answer)
                                                reflection (assoc :reflection reflection)))
                      :usage      (assoc usage :iterations (inc iterations))
                      :status     :answered}})
            ;; Execute non-answer, non-reflect tool calls
            (let [exec-calls (remove #(= :reflect (:tool %)) parsed)
                  results    (if (<= (count exec-calls) 1)
                               [(dispatch-tool meta-db db (first exec-calls))]
                               (pmap #(dispatch-tool meta-db db %) exec-calls))
                  combined   (if (= 1 (count results))
                               (:result (first results))
                               (->> results
                                    (map-indexed (fn [i r]
                                                   (str "Tool result (" (inc i) "/" (count results) "):\n"
                                                        (:result r))))
                                    (str/join "\n\n")))]
              {:messages (tool-result-transition messages (:text response) combined
                                                 iterations max-iterations)
               :steps (conj steps (cond-> (assoc step :tool-result combined)
                                    reflection (assoc :reflection reflection)))
               :iterations (inc iterations)
               :total-usage usage
               :max-iterations max-iterations})))))))

(defn- model-hint
  "If a trained model is available, generate a hint suggesting which
   named queries to try first. Returns a string or nil."
  [meta-db question]
  (when-let [mdl (model/load-best-model)]
    (when-let [suggestions (seq (model/suggest-queries mdl meta-db question 3))]
      (str "\n\nHint: a query routing model suggests these named queries "
           "may be relevant (try them first if they fit):\n"
           (->> suggestions
                (map (fn [{:keys [query-name probability]}]
                       (str "  - " query-name
                            " (confidence: " (format "%.0f%%" (* 100 probability)) ")")))
                (str/join "\n"))))))

(defn ask
  "Run the agent loop: prompt → parse → dispatch → repeat.
   Returns {:answer string :steps vec :usage {:iterations n :input-tokens n :output-tokens n}
            :status :answered|:budget-exhausted :session-id string?}.
   Pass :continue-from session-id to resume a budget-exhausted session.
   Pass :on-iteration (fn [{:keys [iteration max-iterations message]}]) for progress."
  [meta-db db question {:keys [invoke-fn repo-name max-iterations continue-from on-iteration]
                        :or   {max-iterations default-max-iterations}}]
  (let [sys-prompt (build-system-prompt meta-db db repo-name)
        context    {:meta-db meta-db :db db :invoke-fn invoke-fn :system-prompt sys-prompt}
        hint       (model-hint meta-db question)
        user-msg   (if hint (str question hint) question)
        initial    (if-let [prev (when continue-from (load-session! continue-from))]
                     (assoc prev :max-iterations (+ (:iterations prev) max-iterations))
                     {:messages [{:role "user" :content user-msg}]
                      :steps []
                      :iterations 0
                      :total-usage llm/zero-usage
                      :max-iterations max-iterations})]
    (let [start-ms (System/currentTimeMillis)]
      (loop [state initial]
        ;; Pre-step: announce thinking
        (when on-iteration
          (on-iteration {:type    "thinking"
                         :current (:iterations state)
                         :total   (:max-iterations state)
                         :message (if (zero? (:iterations state))
                                    "Reading the question and planning approach..."
                                    "Analyzing results and deciding next step...")}))
        (let [step-start (System/currentTimeMillis)
              nxt        (next-state context state)]
          (if-let [done (:done nxt)]
            (do (when on-iteration
                  (on-iteration {:type      "done"
                                 :current   (get-in done [:usage :iterations])
                                 :total     (get-in done [:usage :iterations])
                                 :message   "Composing final answer..."
                                 :elapsed   (- (System/currentTimeMillis) start-ms)}))
                done)
            (let [elapsed (- (System/currentTimeMillis) step-start)
                  nxt     (update nxt :steps
                                  (fn [ss] (if (seq ss)
                                             (update ss (dec (count ss)) assoc :elapsed-ms elapsed)
                                             ss)))]
              (when on-iteration
                (let [step    (peek (:steps nxt))]
                  (when step
                    (let [parsed  (:parsed step)
                          tools   (when (sequential? parsed) (mapv :tool parsed))
                          args    (when (sequential? parsed) (mapv :args parsed))
                          result  (:tool-result step)
                          raw     (:raw-text step)
                          lines   (when result (str/split-lines result))
                          n       (count (or lines []))
                          ;; Extract 2-3 sample rows for display
                          samples (when (> n 1)
                                    (->> lines (remove str/blank?) (take 3)
                                         (mapv #(subs % 0 (min 100 (count %))))))
                          ;; Extract a one-line reasoning summary from the LLM's raw text
                          ;; The LLM often starts with reasoning before the EDN tool call
                          reasoning (when raw
                                      (let [trimmed (str/trim raw)
                                            ;; Text before the first { or [ is reasoning
                                            idx (min (or (str/index-of trimmed "{") 9999)
                                                     (or (str/index-of trimmed "[") 9999))]
                                        (when (and (pos? idx) (< idx 9999))
                                          (let [text (str/trim (subs trimmed 0 idx))]
                                            (when (seq text)
                                              (subs text 0 (min 150 (count text))))))))
                          ;; Humanize attribute names from query
                          humanize (fn [kw] (str/replace (name kw) #"[-_]" " "))
                          attrs    (when (and (= 1 (count tools)) (= :query (first tools)))
                                     (->> (when-let [q (:query (first args))]
                                            (when (sequential? q) q))
                                          (filter sequential?)
                                          (keep (fn [clause]
                                                  (some #(when (and (keyword? %)
                                                                    (namespace %))
                                                           %)
                                                        clause)))
                                          distinct
                                          (take 3)))
                          desc     (cond
                                     (:error step)
                                     "Adjusting approach after a parsing issue..."

                                     (and (= 1 (count tools)) (= :query (first tools)))
                                     (let [what (if (seq attrs)
                                                  (str/join ", " (map humanize attrs))
                                                  "the knowledge graph")]
                                       (str "Queried " what))

                                     (and (= 1 (count tools)) (= :schema (first tools)))
                                     "Examined the database schema"

                                     (and (= 1 (count tools)) (= :rules (first tools)))
                                     "Looked up query rules"

                                     (seq tools)
                                     (str "Ran " (count tools) " queries in parallel")

                                     :else "Processing...")]
                      (on-iteration {:type      "step"
                                     :current   (:iterations nxt)
                                     :total     (:max-iterations nxt)
                                     :message   desc
                                     :reasoning reasoning
                                     :results   n
                                     :samples   samples
                                     :elapsed   elapsed})))))
              (recur nxt))))))))
