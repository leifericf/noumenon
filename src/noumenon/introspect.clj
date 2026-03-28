(ns noumenon.introspect
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.benchmark :as bench]
            [noumenon.model :as model]
            [noumenon.query :as query]
            [noumenon.training-data :as td]
            [noumenon.util :refer [log!]]))

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

(defn load-history
  "Load improvement history from file. Returns [] if missing."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (edn/read-string (slurp f))
      [])))

;; --- Gap analysis ---

(defn- gap-analysis
  "Analyze benchmark results to identify weakest areas."
  [results]
  (if (empty? results)
    "No baseline data for gap analysis."
    (let [wrong   (filterv #(= :wrong (:score %)) results)
          partial (filterv #(= :partial (:score %)) results)
          correct (filterv #(= :correct (:score %)) results)]
      (str "Score distribution: "
           (count correct) " correct, "
           (count partial) " partial, "
           (count wrong) " wrong\n\n"
           (when (seq wrong)
             (str "WRONG answers (highest priority):\n"
                  (->> wrong
                       (map #(str "  " (name (:id %)) ": " (:reasoning %)))
                       (str/join "\n"))
                  "\n\n"))
           (when (seq partial)
             (str "PARTIAL answers (medium priority):\n"
                  (->> partial
                       (map #(str "  " (name (:id %)) ": " (:reasoning %)))
                       (str/join "\n"))
                  "\n"))))))

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
            (str "Iteration " (inc i) ": target=" (name target)
                 " outcome=" (name outcome)
                 (when delta (str " delta=" (format "%+.3f" delta)))
                 (when goal (str " goal=\"" goal "\""))
                 "\n  " rationale)))
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
        all-queries   (query/list-query-names)
        total-queries (count all-queries)
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
  (try
    (let [cleaned (analyze/strip-markdown-fences text)
          parsed  (edn/read-string cleaned)]
      (when (map? parsed)
        parsed))
    (catch Exception e
      (log! (str "introspect: parse error: " (.getMessage e)))
      nil)))

(defn- valid-query-names []
  (set (query/list-query-names)))

(defn- validate-code-target
  "Validate a :code modification. Returns error string or nil."
  [{:keys [file content]}]
  (cond
    (not (string? file))
    "Code modification must include :file (string path)"

    (not (str/starts-with? file "src/noumenon/"))
    "Code modifications restricted to src/noumenon/ directory"

    (not (str/ends-with? file ".clj"))
    "Code modifications restricted to .clj files"

    (not (string? content))
    "Code modification must include :content (string)"

    :else nil))

(defn validate-proposal
  "Validate a parsed proposal. Returns nil if valid, error string if not."
  [proposal]
  (let [{:keys [target modification rationale]} proposal]
    (cond
      (not (#{:examples :system-prompt :rules :code :train} target))
      "Invalid :target — must be :examples, :system-prompt, :rules, :code, or :train"

      (not (string? rationale))
      "Missing or invalid :rationale"

      (and (= :examples target)
           (not (vector? (:examples modification))))
      "For :examples target, :modification must contain {:examples [...]}"

      (and (= :examples target)
           (let [valid (valid-query-names)
                 names (:examples modification)]
             (some #(not (valid %)) names)))
      (str "Invalid query name(s) in :examples — valid: "
           (str/join ", " (sort (valid-query-names))))

      (and (= :system-prompt target)
           (not (string? (:template modification))))
      "For :system-prompt target, :modification must contain {:template \"...\"}"

      (and (= :system-prompt target)
           (let [tmpl (:template modification)]
             (not-every? #(str/includes? tmpl %)
                         ["{{repo-name}}" "{{schema}}" "{{rules}}" "{{examples}}"])))
      "System prompt template must preserve all {{placeholders}}"

      (and (= :rules target)
           (not (string? (:rules modification))))
      "For :rules target, :modification must contain {:rules \"...edn...\"}"

      (and (= :rules target)
           (try (let [parsed (edn/read-string (:rules modification))]
                  (not (vector? parsed)))
                (catch Exception _ true)))
      "Rules must be a valid EDN vector"

      (and (= :code target)
           (validate-code-target modification))
      (validate-code-target modification)

      (and (= :train target)
           (not (map? (:config modification))))
      "For :train target, :modification must contain {:config {...}}"

      :else nil)))

;; --- Artifact I/O ---

(defn write-examples! [examples]
  (let [path   (resource-path "prompts/agent-examples.edn")
        header (str ";;; Curated subset of named queries for the agent system prompt.\n"
                    ";;; Auto-generated by introspect at " (java.util.Date.) "\n")]
    (spit path (str header (pr-str examples) "\n"))))

(defn write-system-prompt! [template]
  (spit (resource-path "prompts/agent-system.edn")
        (pr-str {:template template})))

(defn write-rules! [rules-str]
  (spit (resource-path "queries/rules.edn") rules-str))

(defn write-code-file! [file content]
  (spit file content))

(defn- read-file-content [path]
  (let [f (io/file path)]
    (when (.exists f) (slurp f))))

(defn- apply-modification!
  "Apply a proposal's modification to disk. Returns the original value for rollback."
  [{:keys [target modification]}]
  (case target
    :examples
    (let [orig (load-current-examples)]
      (write-examples! (:examples modification))
      orig)

    :system-prompt
    (let [orig (load-current-system-prompt)]
      (write-system-prompt! (:template modification))
      orig)

    :rules
    (let [orig (pr-str (load-current-rules))]
      (write-rules! (:rules modification))
      orig)

    :code
    (let [{:keys [file content]} modification
          orig (read-file-content file)]
      (write-code-file! file content)
      orig)

    :train
    (let [orig-config (model/load-config)]
      (spit (resource-path "model/config.edn")
            (pr-str (merge orig-config (:config modification))))
      orig-config)))

(defn- revert-modification!
  "Revert a modification using the saved original."
  [{:keys [target modification]} original]
  (case target
    :examples      (write-examples! original)
    :system-prompt (write-system-prompt! original)
    :rules         (write-rules! original)
    :code          (if original
                     (spit (:file modification) original)
                     (.delete (io/file (:file modification))))
    :train         (spit (resource-path "model/config.edn")
                         (pr-str original))))

;; --- Test gate (for code modifications) ---

(defn- run-tests!
  "Run the test suite. Returns {:pass? bool :output string}."
  []
  (log! "introspect: running test suite...")
  (let [{:keys [exit out err]} (shell/sh "clj" "-M:test")]
    {:pass?  (zero? exit)
     :output (str (when (seq err) (str err "\n")) out)}))

(defn- run-lint!
  "Run the linter. Returns {:pass? bool :output string}."
  []
  (log! "introspect: running linter...")
  (let [{:keys [exit out err]} (shell/sh "clj" "-M:lint")]
    {:pass?  (zero? exit)
     :output (str (when (seq err) err) out)}))

;; --- Git commit ---

(defn- git-commit-improvement!
  "Commit an improvement to git with a descriptive message."
  [repo-path {:keys [target rationale delta]}]
  (let [msg (str "introspect(" (name target) "): " rationale
                 (when delta (str " [" (format "%+.3f" delta) "]")))]
    (log! (str "introspect: committing: " msg))
    (shell/sh "git" "-C" (str repo-path) "add" "-A")
    (shell/sh "git" "-C" (str repo-path) "commit" "-m" msg)))

;; --- Agent-mode evaluation ---

(defn- score-kw->num [kw]
  (case kw :correct 1.0 :partial 0.5 :wrong 0.0 0.0))

(defn evaluate-agent!
  "Evaluate agent performance on deterministic benchmark questions.
   Runs each question through agent/ask, scores deterministically.
   Returns {:mean double :results [{:id kw :score kw :reasoning str}...]}."
  [db repo-name invoke-fn-factory]
  (let [targets   (bench/pick-benchmark-targets db)
        questions (filterv #(= :deterministic (:scoring %))
                           (bench/resolve-question-params
                            (bench/load-questions) targets))
        results   (mapv (fn [q]
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
        scores    (mapv (comp score-kw->num :score) results)]
    {:mean    (if (seq scores) (/ (reduce + scores) (count scores)) 0.0)
     :results results}))

;; --- History ---

(defn- ensure-parent! [path]
  (.mkdirs (.getParentFile (io/file path))))

(defn append-history!
  "Append an iteration record to the history file."
  [path record]
  (ensure-parent! path)
  (let [history (load-history path)]
    (spit path (pr-str (conj history record)))))

;; --- Iteration ---

(defn run-iteration!
  "Run a single introspect iteration.
   Returns {:outcome :improved/:reverted/:skipped :record map}."
  [{:keys [db repo-name repo-path invoke-fn-factory optimizer-invoke-fn
           baseline history history-path git-commit?]}]
  (let [;; Load current artifacts
        orig-examples (load-current-examples)
        orig-system   (load-current-system-prompt)
        orig-rules    (load-current-rules)
        ;; Build meta-prompt with gap analysis
        meta-prompt (build-meta-prompt
                     {:system-prompt    orig-system
                      :examples         orig-examples
                      :rules            orig-rules
                      :history          history
                      :baseline-results (:results baseline)})
        ;; Ask optimizer for proposal
        _           (log! "introspect: requesting proposal from optimizer...")
        response    (optimizer-invoke-fn
                     [{:role "user" :content meta-prompt}])
        proposal    (parse-proposal (:text response))]
    (if-not proposal
      (do (log! "introspect: failed to parse proposal, skipping")
          {:outcome :skipped
           :record  {:timestamp (str (java.util.Date.))
                     :outcome   :skipped
                     :rationale "Parse failure"}})
      (if-let [err (validate-proposal proposal)]
        (do (log! (str "introspect: invalid proposal: " err))
            {:outcome :skipped
             :record  {:timestamp (str (java.util.Date.))
                       :outcome   :skipped
                       :rationale (str "Validation: " err)}})
        (let [{:keys [target modification rationale goal]} proposal
              _ (log! (str "introspect: target=" (name target)
                           " goal=" (pr-str goal)
                           "\n  " rationale))
              ;; Apply modification
              original (apply-modification! proposal)
              ;; For code changes, run lint + tests as gate
              code-gate-ok?
              (if (= :code target)
                (let [lint-r (run-lint!)
                      test-r (when (:pass? lint-r) (run-tests!))]
                  (cond
                    (not (:pass? lint-r))
                    (do (log! "introspect: lint FAILED, reverting")
                        (log! (str "  " (subs (:output lint-r) 0
                                              (min 200 (count (:output lint-r))))))
                        false)
                    (not (:pass? test-r))
                    (do (log! "introspect: tests FAILED, reverting")
                        false)
                    :else true))
                true)]
          (if-not code-gate-ok?
            (do (revert-modification! proposal original)
                (let [record {:timestamp (str (java.util.Date.))
                              :target    target :goal goal
                              :rationale rationale
                              :outcome   :gate-failed}]
                  (append-history! history-path record)
                  {:outcome :gate-failed :record record}))
            (do
              ;; For :train target, build dataset and train the model
              (when (= :train target)
                (let [config  (model/load-config)
                      dataset (td/build-dataset db config)
                      mdl     (model/init-model config)
                      _       (model/train! mdl dataset config)
                      eval-r  (model/evaluate mdl dataset)]
                  (log! (str "introspect: model accuracy="
                             (format "%.3f" (:accuracy eval-r))
                             " top3=" (format "%.3f" (:top3-accuracy eval-r))))
                  (model/save-model! mdl "data/models/latest.edn")))
              ;; Reset agent's cached prompts (for prompt/example/rule changes)
              (when (#{:examples :system-prompt :rules} target)
                (agent/reset-prompt-cache!))
              ;; Evaluate
              (log! "introspect: evaluating...")
              (let [eval-result (evaluate-agent! db repo-name invoke-fn-factory)
                    new-mean    (:mean eval-result)
                    base-mean   (:mean baseline)
                    delta       (- new-mean base-mean)
                    improved?   (> delta 0.001)]
                (if improved?
                  (do (log! (str "introspect: IMPROVED " (format "%+.3f" delta)
                                 " (" (format "%.3f" base-mean)
                                 " -> " (format "%.3f" new-mean) ")"))
                      (let [record {:timestamp    (str (java.util.Date.))
                                    :target       target :goal goal
                                    :rationale    rationale
                                    :baseline     base-mean
                                    :result       new-mean
                                    :delta        delta
                                    :outcome      :improved
                                    :modification modification}]
                        (append-history! history-path record)
                        (when git-commit?
                          (git-commit-improvement! repo-path record))
                        {:outcome     :improved
                         :record      record
                         :eval-result eval-result}))
                  (do (log! (str "introspect: reverted (delta=" (format "%+.3f" delta) ")"))
                      (revert-modification! proposal original)
                      (when (#{:examples :system-prompt :rules} target)
                        (agent/reset-prompt-cache!))
                      (let [record {:timestamp (str (java.util.Date.))
                                    :target    target :goal goal
                                    :rationale rationale
                                    :baseline  base-mean
                                    :result    new-mean
                                    :delta     delta
                                    :outcome   :reverted}]
                        (append-history! history-path record)
                        {:outcome :reverted :record record})))))))))))

;; --- Main loop ---

(defn run-loop!
  "Run the introspect improvement loop.
   Returns {:iterations n :improvements n :final-score double :history vec}."
  [{:keys [db repo-name repo-path invoke-fn-factory optimizer-invoke-fn
           max-iterations max-hours max-cost history-path git-commit?]
    :or   {max-iterations 10
           history-path   "data/introspect/history.edn"}}]
  (let [start-ms (System/currentTimeMillis)
        max-ms   (when max-hours (* max-hours 3600000))
        history  (load-history history-path)
        _        (log! "introspect: running baseline evaluation...")
        baseline (evaluate-agent! db repo-name invoke-fn-factory)
        _        (log! (str "introspect: baseline mean=" (format "%.3f" (:mean baseline))))]
    (loop [i            0
           baseline     baseline
           history      history
           improvements 0
           total-cost   0.0]
      (let [elapsed-ms (- (System/currentTimeMillis) start-ms)
            time-up?   (and max-ms (> elapsed-ms max-ms))
            cost-up?   (and max-cost (> total-cost max-cost))]
        (cond
          (>= i max-iterations)
          (do (log! (str "introspect: reached max iterations (" max-iterations ")"))
              {:iterations i :improvements improvements
               :final-score (:mean baseline) :history history})

          time-up?
          (do (log! "introspect: time budget exhausted")
              {:iterations i :improvements improvements
               :final-score (:mean baseline) :history history})

          cost-up?
          (do (log! (str "introspect: cost budget exhausted ($"
                         (format "%.2f" total-cost) ")"))
              {:iterations i :improvements improvements
               :final-score (:mean baseline) :history history})

          :else
          (do (log! (str "\nintrospect: === Iteration " (inc i) "/"
                         max-iterations " ==="))
              (let [{:keys [outcome eval-result record]}
                    (run-iteration!
                     {:db                  db
                      :repo-name           repo-name
                      :repo-path           repo-path
                      :invoke-fn-factory   invoke-fn-factory
                      :optimizer-invoke-fn optimizer-invoke-fn
                      :baseline            baseline
                      :history             history
                      :history-path        history-path
                      :git-commit?         git-commit?})
                    new-baseline (if (= :improved outcome)
                                   eval-result
                                   baseline)]
                (recur (inc i)
                       new-baseline
                       (conj history record)
                       (if (= :improved outcome) (inc improvements) improvements)
                       total-cost))))))))
