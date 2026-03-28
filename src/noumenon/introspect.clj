(ns noumenon.introspect
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.benchmark :as bench]
            [noumenon.git :as git]
            [noumenon.model :as model]
            [noumenon.query :as query]
            [noumenon.training-data :as td]
            [noumenon.util :as util :refer [log!]]))

;; --- Internal meta database ---

(defn- generate-run-id []
  (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID)))

;; --- Loading ---

(defn load-meta-prompt []
  (:template (query/load-edn-resource "prompts/introspect.edn")))

(defn load-current-examples []
  (query/load-edn-resource "prompts/agent-examples.edn"))

(defn load-current-system-prompt []
  (:template (query/load-edn-resource "prompts/agent-system.edn")))

(defn load-current-rules []
  (query/load-edn-resource "queries/rules.edn"))

(defn- resource-path [resource-name]
  (str (.getFile (io/resource resource-name))))

;; --- History from Datomic ---

(defn load-history
  "Load improvement history from the meta database. Returns [] if empty."
  [meta-db]
  (->> (d/q '[:find ?idx ?target ?outcome ?rationale ?delta ?goal
              :where
              [?r :introspect.run/iterations ?i]
              [?i :introspect.iter/index ?idx]
              [?i :introspect.iter/outcome ?outcome]
              [(get-else $ ?i :introspect.iter/target :unknown) ?target]
              [(get-else $ ?i :introspect.iter/rationale "") ?rationale]
              [(get-else $ ?i :introspect.iter/delta 0.0) ?delta]
              [(get-else $ ?i :introspect.iter/goal "") ?goal]]
            meta-db)
       (sort-by first)
       (mapv (fn [[_ target outcome rationale delta goal]]
               {:target target :outcome outcome :rationale rationale
                :delta delta :goal goal}))))

;; --- Gap analysis ---

(defn- gap-analysis [results]
  (if (empty? results)
    "No baseline data for gap analysis."
    (let [by-score (group-by :score results)
          section  (fn [label kw]
                     (when-let [items (seq (by-score kw))]
                       (str label ":\n"
                            (->> items
                                 (map #(str "  " (name (:id %)) ": " (:reasoning %)))
                                 (str/join "\n"))
                            "\n\n")))]
      (str "Score distribution: "
           (count (by-score :correct)) " correct, "
           (count (by-score :partial)) " partial, "
           (count (by-score :wrong)) " wrong\n\n"
           (section "WRONG answers (highest priority)" :wrong)
           (section "PARTIAL answers (medium priority)" :partial)))))

;; --- Meta-prompt assembly ---

(defn- format-scores [results]
  (if (empty? results)
    "No baseline scores yet."
    (->> results
         (map (fn [{:keys [id score reasoning]}]
                (str "  " (name id) ": " (name score)
                     (when reasoning (str " — " reasoning)))))
         (str/join "\n"))))

(defn- format-history [history]
  (if (empty? history)
    "No prior iterations."
    (->> history
         (map-indexed
          (fn [i {:keys [target rationale outcome delta goal]}]
            (str "Iteration " (inc i)
                 ": target=" (if target (name target) "unknown")
                 " outcome=" (if outcome (name outcome) "unknown")
                 (when delta (str " delta=" (format "%+.3f" (double delta))))
                 (when goal (str " goal=\"" goal "\""))
                 "\n  " (or rationale "no rationale"))))
         (str/join "\n\n"))))

(defn- format-query-catalog []
  (->> (query/list-query-names)
       (keep query/load-named-query)
       (map (fn [{:keys [name description]}]
              (str "  " name " — " description)))
       (str/join "\n")))

(defn build-meta-prompt
  "Assemble the meta-prompt for the optimizer LLM."
  [{:keys [system-prompt examples rules history baseline-results]}]
  (let [template      (load-meta-prompt)
        total-queries (count (query/list-query-names))
        base-mean     (if (seq baseline-results)
                        (let [scores (map (fn [{:keys [score]}]
                                            (case score :correct 1.0 :partial 0.5 0.0))
                                          baseline-results)]
                          (/ (reduce + scores) (count scores)))
                        0.0)]
    (-> template
        (str/replace "{{current-system-prompt}}" (or system-prompt ""))
        (str/replace "{{current-examples}}" (pr-str (or examples [])))
        (str/replace "{{example-count}}" (str (count (or examples []))))
        (str/replace "{{total-queries}}" (str total-queries))
        (str/replace "{{all-queries}}" (format-query-catalog))
        (str/replace "{{current-rules}}" (pr-str (or rules [])))
        (str/replace "{{baseline-mean}}" (format "%.3f" base-mean))
        (str/replace "{{baseline-scores}}" (format-scores baseline-results))
        (str/replace "{{gap-analysis}}" (gap-analysis baseline-results))
        (str/replace "{{history}}" (format-history history)))))

;; --- Proposal parsing ---

(defn parse-proposal
  "Parse the optimizer LLM's response into a proposal map."
  [text]
  (when text
    (try
      (let [cleaned (analyze/strip-markdown-fences text)
            parsed  (edn/read-string cleaned)]
        (when (map? parsed) parsed))
      (catch Exception e
        (log! (str "introspect: parse error: " (.getMessage e)))
        nil))))

(defn- valid-query-names []
  (set (query/list-query-names)))

(defn validate-proposal
  "Validate a parsed proposal. Returns nil if valid, error string if not."
  [proposal]
  (let [{:keys [target modification rationale]} proposal]
    (cond
      (not (#{:examples :system-prompt :rules :code :train} target))
      "Invalid :target — must be :examples, :system-prompt, :rules, :code, or :train"

      (not (string? rationale))
      "Missing or invalid :rationale"

      ;; --- :examples ---
      (and (= :examples target)
           (not (vector? (:examples modification))))
      "For :examples target, :modification must contain {:examples [...]}"

      (and (= :examples target)
           (some (complement (valid-query-names)) (:examples modification)))
      (str "Invalid query name(s) in :examples — valid: "
           (str/join ", " (sort (valid-query-names))))

      ;; --- :system-prompt ---
      (and (= :system-prompt target)
           (not (string? (:template modification))))
      "For :system-prompt target, :modification must contain {:template \"...\"}"

      (and (= :system-prompt target)
           (not-every? #(str/includes? (:template modification) %)
                       ["{{repo-name}}" "{{schema}}" "{{rules}}" "{{examples}}"]))
      "System prompt template must preserve all {{placeholders}}"

      ;; --- :rules ---
      (and (= :rules target)
           (not (string? (:rules modification))))
      "For :rules target, :modification must contain {:rules \"...edn...\"}"

      (and (= :rules target)
           (try (not (vector? (edn/read-string (:rules modification))))
                (catch Exception _ true)))
      "Rules must be a valid EDN vector"

      ;; --- :code ---
      (and (= :code target) (not (string? (:file modification))))
      "Code modification must include :file (string path)"

      (and (= :code target) (not (str/starts-with? (:file modification "") "src/noumenon/")))
      "Code modifications restricted to src/noumenon/ directory"

      (and (= :code target) (str/includes? (str (:file modification)) ".."))
      "Code modification path must not contain '..'"

      (and (= :code target) (not (str/ends-with? (:file modification "") ".clj")))
      "Code modifications restricted to .clj files"

      (and (= :code target) (not (string? (:content modification))))
      "Code modification must include :content (string)"

      ;; --- :train ---
      (and (= :train target) (not (map? (:config modification))))
      "For :train target, :modification must contain {:config {...}}"

      :else nil)))

;; --- Artifact I/O (multimethods — open for extension) ---

(defn- save-raw
  "Read the raw file bytes for a resource. Used for exact rollback."
  [resource-name]
  (slurp (resource-path resource-name)))

(defmulti apply-modification!
  "Apply a proposal's modification to disk. Returns the original for rollback."
  (fn [proposal] (:target proposal)))

(defmulti revert-modification!
  "Revert a modification by restoring the original."
  (fn [proposal _original] (:target proposal)))

(defmethod apply-modification! :examples [{:keys [modification]}]
  (let [orig (save-raw "prompts/agent-examples.edn")
        header (str ";;; Curated subset of named queries for the agent system prompt.\n"
                    ";;; Auto-generated by introspect at " (java.util.Date.) "\n")]
    (spit (resource-path "prompts/agent-examples.edn")
          (str header (pr-str (:examples modification)) "\n"))
    orig))

(defmethod revert-modification! :examples [_ original]
  (spit (resource-path "prompts/agent-examples.edn") original))

(defmethod apply-modification! :system-prompt [{:keys [modification]}]
  (let [orig (save-raw "prompts/agent-system.edn")]
    (spit (resource-path "prompts/agent-system.edn")
          (pr-str {:template (:template modification)}))
    orig))

(defmethod revert-modification! :system-prompt [_ original]
  (spit (resource-path "prompts/agent-system.edn") original))

(defmethod apply-modification! :rules [{:keys [modification]}]
  (let [orig (save-raw "queries/rules.edn")]
    (spit (resource-path "queries/rules.edn") (:rules modification))
    orig))

(defmethod revert-modification! :rules [_ original]
  (spit (resource-path "queries/rules.edn") original))

(defmethod apply-modification! :code [{:keys [modification]}]
  (let [{:keys [file content]} modification
        f (io/file file)
        orig (when (.exists f) (slurp f))]
    (spit file content)
    orig))

(defmethod revert-modification! :code [{:keys [modification]} original]
  (if original
    (spit (:file modification) original)
    (.delete (io/file (:file modification)))))

(defmethod apply-modification! :train [{:keys [modification]}]
  (let [orig (save-raw "model/config.edn")]
    (spit (resource-path "model/config.edn")
          (pr-str (merge (model/load-config) (:config modification))))
    orig))

(defmethod revert-modification! :train [_ original]
  (spit (resource-path "model/config.edn") original))

;; --- with-modification macro ---

(defmacro with-modification
  "Apply a modification, execute body, revert on :revert result or exception.
   Binds `original` in body for inspection. Body must return a map with :outcome.
   If outcome is not :improved, reverts automatically."
  [proposal & body]
  `(let [proposal# ~proposal
         original# (apply-modification! proposal#)]
     (try
       (let [result# (do ~@body)]
         (when-not (= :improved (:outcome result#))
           (revert-modification! proposal# original#)
           (when (#{:examples :system-prompt :rules} (:target proposal#))
             (agent/reset-prompt-cache!)))
         result#)
       (catch Exception e#
         (log! (str "introspect: ERROR, reverting: " (.getMessage e#)))
         (revert-modification! proposal# original#)
         (when (#{:examples :system-prompt :rules} (:target proposal#))
           (agent/reset-prompt-cache!))
         {:outcome :error
          :record  {:target    (:target proposal#)
                    :goal      (:goal proposal#)
                    :rationale (:rationale proposal#)
                    :outcome   :error
                    :error     (.getMessage e#)}}))))

;; --- Code verification (in-process) ---

(defn- verify-code-syntax
  "Verify that proposed code is valid Clojure. Returns nil if ok, error string if not.
   Uses read-string to parse — catches syntax errors without a subprocess."
  [content]
  (try
    (let [rdr (java.io.PushbackReader. (java.io.StringReader. content))]
      (loop []
        (let [form (read {:eof ::done} rdr)]
          (if (= ::done form)
            nil ;; all forms read successfully
            (recur)))))
    (catch Exception e
      (str "Syntax error: " (.getMessage e)))))

(defn- verify-code-compiles
  "Attempt to compile the modified namespace by requiring it with :reload.
   Returns nil if ok, error string if compilation fails."
  [file]
  (try
    (let [;; Derive namespace from file path: src/noumenon/foo.clj -> noumenon.foo
          ns-name (-> file
                      (str/replace #"^src/" "")
                      (str/replace #"\.clj$" "")
                      (str/replace "/" ".")
                      (str/replace "_" "-")
                      symbol)]
      (require ns-name :reload)
      nil)
    (catch Exception e
      (str "Compilation error: " (.getMessage e)))))

(defn- run-code-gate!
  "Verify a code modification: syntax, compilation, lint, tests.
   Checks in-process first (fast), falls back to subprocess only for lint.
   Returns {:pass? bool :error string?}."
  [file content]
  (if-let [syntax-err (verify-code-syntax content)]
    (do (log! (str "introspect: syntax check FAILED: " syntax-err))
        {:pass? false :error syntax-err})
    (if-let [compile-err (verify-code-compiles file)]
      (do (log! (str "introspect: compilation FAILED: " compile-err))
          {:pass? false :error compile-err})
      (let [{:keys [exit err]} (shell/sh "clj" "-M:lint")]
        (if (zero? exit)
          {:pass? true}
          (do (log! (str "introspect: lint FAILED"))
              {:pass? false :error (str "Lint: " (subs (str err) 0 (min 200 (count (str err)))))}))))))

;; --- Git commit ---

(def ^:private committable-paths
  ["resources/prompts/" "resources/queries/" "resources/model/" "src/noumenon/"])

(defn- git-commit-improvement! [repo-path {:keys [target rationale delta]}]
  (let [msg (str "introspect(" (name target) "): " rationale
                 (when delta (str " [" (format "%+.3f" (double delta)) "]")))]
    (log! (str "introspect: committing: " msg))
    (doseq [p committable-paths]
      (shell/sh "git" "-C" (str repo-path) "add" p))
    (shell/sh "git" "-C" (str repo-path) "commit" "-m" msg)))

;; --- Agent-mode evaluation ---

(defn- score-kw->num [kw]
  (case kw :correct 1.0 :partial 0.5 :wrong 0.0 0.0))

(defn- evaluate-once!
  "Run one evaluation pass. Returns {:mean double :results [...]}"
  [db repo-name invoke-fn-factory questions]
  (let [results (mapv (fn [q]
                        (log! (str "  eval: " (name (:id q))))
                        (let [{:keys [answer]}
                              (agent/ask db (:question q)
                                         {:invoke-fn      (invoke-fn-factory)
                                          :repo-name      repo-name
                                          :max-iterations 6})
                              {:keys [score reasoning]}
                              (bench/deterministic-score q db (or answer ""))]
                          {:id (:id q) :score score :reasoning reasoning}))
                      questions)
        scores  (mapv (comp score-kw->num :score) results)]
    {:mean    (if (seq scores) (/ (reduce + scores) (count scores)) 0.0)
     :results results}))

(defn- median [nums]
  (let [sorted (sort nums)
        n      (count sorted)]
    (if (odd? n)
      (nth sorted (quot n 2))
      (/ (+ (nth sorted (dec (quot n 2)))
            (nth sorted (quot n 2)))
         2.0))))

(defn evaluate-agent!
  "Evaluate agent performance on deterministic benchmark questions.
   Runs eval-runs times (default 1) and takes the median score to
   reduce variance from LLM non-determinism.
   Returns {:mean double :results [{:id kw :score kw :reasoning str}...]}."
  [db repo-name invoke-fn-factory & {:keys [eval-runs] :or {eval-runs 1}}]
  (let [targets   (bench/pick-benchmark-targets db)
        questions (filterv #(= :deterministic (:scoring %))
                           (bench/resolve-question-params
                            (bench/load-questions) targets))]
    (if (<= eval-runs 1)
      (evaluate-once! db repo-name invoke-fn-factory questions)
      (let [runs   (mapv (fn [i]
                           (log! (str "  eval pass " (inc i) "/" eval-runs))
                           (evaluate-once! db repo-name invoke-fn-factory questions))
                         (range eval-runs))
            med    (median (mapv :mean runs))
            ;; Return results from the run closest to the median
            best   (apply min-key #(Math/abs (- (:mean %) med)) runs)]
        (log! (str "  median of " eval-runs " runs: " (format "%.3f" med)))
        best))))

;; --- Datomic transaction builders (pure) ---

(defn- iter->tx-data [index {:keys [target goal rationale outcome baseline
                                    result delta modification error]}]
  (let [base {:introspect.iter/index     (long index)
              :introspect.iter/outcome   (or outcome :unknown)
              :introspect.iter/timestamp (java.util.Date.)}]
    (cond-> base
      target    (assoc :introspect.iter/target target)
      goal      (assoc :introspect.iter/goal goal)
      rationale (assoc :introspect.iter/rationale rationale)
      baseline  (assoc :introspect.iter/baseline-mean (double baseline))
      result    (assoc :introspect.iter/result-mean (double result))
      delta     (assoc :introspect.iter/delta (double delta))
      error     (assoc :introspect.iter/error error)
      (and (= :improved outcome) modification)
      (assoc :introspect.iter/modification (pr-str modification)))))

(defn run->tx-data
  "Build Datomic tx-data for a completed introspect run. Pure function."
  [{:keys [run-id repo-path commit-sha started-at model-config
           max-iterations prompt-hash examples-hash rules-hash
           db-basis-t baseline-mean final-mean iteration-count
           improvement-count cost-usd iter-records]}]
  (let [iter-entities (vec (map-indexed iter->tx-data iter-records))
        base {:introspect.run/id                run-id
              :introspect.run/repo-path         (str repo-path)
              :introspect.run/started-at        (or started-at (java.util.Date.))
              :introspect.run/completed-at      (java.util.Date.)
              :introspect.run/duration-ms       (long (- (System/currentTimeMillis)
                                                         (.getTime ^java.util.Date
                                                          (or started-at (java.util.Date.)))))
              :introspect.run/iteration-count   (long iteration-count)
              :introspect.run/improvement-count (long improvement-count)
              :introspect.run/iterations        iter-entities}
        run-entity
        (cond-> base
          commit-sha     (assoc :introspect.run/commit-sha commit-sha)
          model-config   (assoc :introspect.run/model-config (pr-str model-config))
          max-iterations (assoc :introspect.run/max-iterations (long max-iterations))
          prompt-hash    (assoc :introspect.run/prompt-hash prompt-hash)
          examples-hash  (assoc :introspect.run/examples-hash examples-hash)
          rules-hash     (assoc :introspect.run/rules-hash rules-hash)
          db-basis-t     (assoc :introspect.run/db-basis-t (long db-basis-t))
          baseline-mean  (assoc :introspect.run/baseline-mean (double baseline-mean))
          final-mean     (assoc :introspect.run/final-mean (double final-mean))
          cost-usd       (assoc :introspect.run/cost-usd (double cost-usd)))]
    [run-entity
     {:db/id "datomic.tx" :tx/op :introspect}]))

;; --- Iteration ---

(defn- make-record [proposal outcome & {:as extra}]
  (merge {:target    (:target proposal)
          :goal      (:goal proposal)
          :rationale (:rationale proposal)
          :outcome   outcome}
         extra))

(defn run-iteration!
  "Run a single introspect iteration.
   Returns {:outcome kw :record map :eval-result map?}."
  [{:keys [db repo-name repo-path invoke-fn-factory optimizer-invoke-fn
           baseline history git-commit? allowed-targets eval-runs]}]
  (let [meta-prompt (build-meta-prompt
                     {:system-prompt    (load-current-system-prompt)
                      :examples         (load-current-examples)
                      :rules            (load-current-rules)
                      :history          history
                      :baseline-results (:results baseline)})
        response    (try
                      (log! "introspect: requesting proposal from optimizer...")
                      (optimizer-invoke-fn [{:role "user" :content meta-prompt}])
                      (catch Exception e
                        (log! (str "introspect: optimizer error: " (.getMessage e)))
                        nil))
        proposal    (parse-proposal (:text response))]
    (cond
      ;; No proposal — skip
      (nil? proposal)
      (do (log! "introspect: failed to parse proposal, skipping")
          {:outcome :skipped :record {:outcome :skipped :rationale "Parse failure"}})

      ;; Invalid proposal — skip
      (validate-proposal proposal)
      (let [err (validate-proposal proposal)]
        (log! (str "introspect: invalid proposal: " err))
        {:outcome :skipped :record {:outcome :skipped :rationale (str "Validation: " err)}})

      ;; Target not in allowed set — skip
      (and allowed-targets (not (allowed-targets (:target proposal))))
      (let [t (:target proposal)]
        (log! (str "introspect: target " (name t) " not in allowed set "
                   (pr-str allowed-targets) ", skipping"))
        {:outcome :skipped
         :record  {:outcome :skipped
                   :rationale (str "Target " (name t) " not allowed")}})

      ;; Valid proposal — apply, gate, evaluate, decide
      :else
      (let [{:keys [target modification rationale goal]} proposal
            _ (log! (str "introspect: target=" (name target)
                         " goal=" (pr-str goal) "\n  " rationale))]
        ;; with-modification handles apply, revert-on-failure, and exception recovery
        (with-modification proposal
          ;; Code gate: syntax → compile → lint (in-process where possible)
          (if (and (= :code target)
                   (not (:pass? (run-code-gate! (:file modification) (:content modification)))))
            {:outcome :gate-failed :record (make-record proposal :gate-failed)}

            (do
              ;; Train model if target is :train
              (when (= :train target)
                (let [config  (model/load-config)
                      dataset (td/build-dataset db config)
                      prev    (model/load-best-model)
                      mdl     (if (and prev (= (:config prev) config))
                                (do (log! "introspect: warm-starting from previous model")
                                    prev)
                                (model/init-model config))
                      _       (model/train! mdl dataset config)
                      eval-r  (model/evaluate mdl dataset)]
                  (log! (str "introspect: model accuracy="
                             (format "%.3f" (:accuracy eval-r))
                             " top3=" (format "%.3f" (:top3-accuracy eval-r))))
                  (model/save-model! (assoc mdl :vocab (:vocab dataset))
                                     "data/models/latest.edn")))

              ;; Reset prompt cache for prompt/example/rule changes
              (when (#{:examples :system-prompt :rules} target)
                (agent/reset-prompt-cache!))

              ;; Evaluate
              (log! "introspect: evaluating...")
              (let [eval-result (evaluate-agent! db repo-name invoke-fn-factory
                                                 :eval-runs (or eval-runs 1))
                    new-mean    (:mean eval-result)
                    base-mean   (:mean baseline)
                    delta       (- new-mean base-mean)
                    improved?   (> delta 0.001)]
                (if improved?
                  (do (log! (str "introspect: IMPROVED " (format "%+.3f" delta)
                                 " (" (format "%.3f" base-mean)
                                 " -> " (format "%.3f" new-mean) ")"))
                      (when git-commit?
                        (git-commit-improvement! repo-path
                                                 {:target target :rationale rationale
                                                  :delta delta}))
                      {:outcome     :improved
                       :record      (make-record proposal :improved
                                                 :baseline base-mean :result new-mean
                                                 :delta delta :modification modification)
                       :eval-result eval-result})
                  (do (log! (str "introspect: reverted (delta=" (format "%+.3f" delta) ")"))
                      {:outcome :reverted
                       :record  (make-record proposal :reverted
                                             :baseline base-mean :result new-mean
                                             :delta delta)}))))))))))

;; --- Main loop ---

(defn run-loop!
  "Run the introspect improvement loop.
   Persists results to the internal meta database.
   Returns {:run-id str :iterations n :improvements n :final-score double}."
  [{:keys [db repo-name repo-path invoke-fn-factory optimizer-invoke-fn
           meta-conn max-iterations max-hours max-cost git-commit?
           model-config allowed-targets eval-runs]
    :or   {max-iterations 10 eval-runs 1}}]
  (let [run-id        (generate-run-id)
        start-ms      (System/currentTimeMillis)
        started-at    (java.util.Date.)
        max-ms        (when max-hours (* max-hours 3600000))
        history       (load-history (d/db meta-conn))
        prompt-hash   (util/sha256-hex (or (load-current-system-prompt) ""))
        examples-hash (util/sha256-hex (pr-str (or (load-current-examples) [])))
        rules-hash    (util/sha256-hex (pr-str (or (load-current-rules) [])))
        commit-sha    (git/head-sha repo-path)
        db-basis-t    (try (:t (d/db-stats db)) (catch Exception _ nil))
        _             (log! "introspect: running baseline evaluation...")
        baseline      (evaluate-agent! db repo-name invoke-fn-factory
                                       :eval-runs eval-runs)
        _             (log! (str "introspect: baseline mean="
                                 (format "%.3f" (:mean baseline))))
        budget-done?  (fn [i cost]
                        (let [elapsed (- (System/currentTimeMillis) start-ms)]
                          (cond
                            (>= i max-iterations)
                            (str "reached max iterations (" max-iterations ")")
                            (and max-ms (> elapsed max-ms))
                            "time budget exhausted"
                            (and max-cost (> cost max-cost))
                            (str "cost budget exhausted ($" (format "%.2f" cost) ")"))))
        result
        (loop [i 0, baseline baseline, history history,
               improvements 0, cost 0.0, iter-records []]
          (if-let [reason (budget-done? i cost)]
            (do (log! (str "introspect: " reason))
                {:iterations i :improvements improvements
                 :final-score (:mean baseline) :iter-records iter-records})
            (do (log! (str "\nintrospect: === Iteration " (inc i) "/"
                           max-iterations " ==="))
                (let [{:keys [outcome eval-result record]}
                      (run-iteration!
                       {:db db :repo-name repo-name :repo-path repo-path
                        :invoke-fn-factory invoke-fn-factory
                        :optimizer-invoke-fn optimizer-invoke-fn
                        :baseline baseline :history history
                        :git-commit? git-commit?
                        :allowed-targets allowed-targets
                        :eval-runs eval-runs})
                      new-baseline (if (= :improved outcome) eval-result baseline)]
                  (recur (inc i) new-baseline (conj history record)
                         (if (= :improved outcome) (inc improvements) improvements)
                         cost (conj iter-records record))))))
        tx-data
        (run->tx-data
         {:run-id run-id :repo-path repo-path :commit-sha commit-sha
          :started-at started-at :model-config model-config
          :max-iterations max-iterations :prompt-hash prompt-hash
          :examples-hash examples-hash :rules-hash rules-hash
          :db-basis-t db-basis-t :baseline-mean (:mean baseline)
          :final-mean (:final-score result)
          :iteration-count (:iterations result)
          :improvement-count (:improvements result)
          :cost-usd 0.0 :iter-records (:iter-records result)})]
    (d/transact meta-conn {:tx-data tx-data})
    (log! (str "introspect: persisted run " run-id " to meta database"))
    (assoc result :run-id run-id)))
