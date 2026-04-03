(ns noumenon.introspect
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.benchmark :as bench]
            [noumenon.git :as git]
            [noumenon.model :as model]
            [noumenon.ask-store :as ask-store]
            [noumenon.training-data :as td]
            [noumenon.util :as util :refer [log!]]))

;; --- Internal meta database ---

(defn- generate-run-id []
  (str (System/currentTimeMillis) "-" (java.util.UUID/randomUUID)))

;; --- Loading ---

(defn load-meta-prompt [meta-db]
  (artifacts/load-prompt meta-db "introspect"))

(defn load-current-examples [meta-db]
  (some->> (artifacts/load-prompt meta-db "agent-examples")
           (edn/read-string {:readers {}})))

(defn load-current-system-prompt [meta-db]
  (artifacts/load-prompt meta-db "agent-system"))

(defn load-current-rules [meta-db]
  (artifacts/load-rules meta-db))

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
       (take-last 100)
       (mapv (fn [[_ target outcome rationale delta goal]]
               {:target target :outcome outcome :rationale rationale
                :delta delta :goal goal}))))

;; --- Gap analysis ---

(defn- gap-analysis [results]
  (if (seq results)
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
           (section "PARTIAL answers (medium priority)" :partial)))
    "No baseline data for gap analysis."))

(defn- summarize-feedback-items
  "Parse EDN data, aggregate by frequency, and format as a titled section."
  [data title description verb take-n]
  (when (seq data)
    (let [all-items (->> data
                         (mapcat (fn [[s]] (when (<= (count (str s)) 10000)
                                             (try (edn/read-string {:readers {}} s) (catch Exception _ [])))))
                         frequencies
                         (sort-by val >))]
      (when (seq all-items)
        (str "### " title " (" (count all-items) " distinct)\n"
             (when description (str description "\n"))
             (->> all-items (take take-n)
                  (map (fn [[item cnt]] (str "  - " item " (" verb " " cnt "x)")))
                  (str/join "\n"))
             "\n\n")))))

(defn- fetch-ask-data
  "Query all ask session data needed for insights. Returns a data map."
  [meta-db]
  (let [sessions (ask-store/list-sessions meta-db :limit 200)]
    {:sessions      sessions
     :unanswered    (filterv #(= :budget-exhausted (:status %)) sessions)
     :errors        (d/q '[:find ?question
                           :where
                           [?e :ask.session/question ?question]
                           [?e :ask.session/steps ?step]
                           [?step :ask.step/type :error]]
                         meta-db)
     :empty-qs      (d/q '[:find ?question ?query-edn
                           :where
                           [?e :ask.session/question ?question]
                           [?e :ask.session/steps ?step]
                           [?step :ask.step/type :query]
                           [?step :ask.step/query-edn ?query-edn]
                           [?step :ask.step/result-count 0]]
                         meta-db)
     :popular       (d/q '[:find ?query-edn (count ?step)
                           :where
                           [?step :ask.step/type :query]
                           [?step :ask.step/query-edn ?query-edn]]
                         meta-db)
     :neg-fb        (d/q '[:find ?question ?comment ?answer ?missing ?quality ?notes
                           :where
                           [?e :ask.session/feedback :negative]
                           [?e :ask.session/question ?question]
                           [(get-else $ ?e :ask.session/feedback-comment "") ?comment]
                           [(get-else $ ?e :ask.session/answer "") ?answer]
                           [(get-else $ ?e :ask.session/missing-attributes "") ?missing]
                           [(get-else $ ?e :ask.session/quality-issues "") ?quality]
                           [(get-else $ ?e :ask.session/agent-notes "") ?notes]]
                         meta-db)
     :missing-attrs (d/q '[:find ?attrs
                           :where [?e :ask.session/missing-attributes ?attrs]]
                         meta-db)
     :quality-iss   (d/q '[:find ?issues
                           :where [?e :ask.session/quality-issues ?issues]]
                         meta-db)
     :suggested-qs  (d/q '[:find ?qs
                           :where [?e :ask.session/suggested-queries ?qs]]
                         meta-db)}))

(defn- format-ask-insights
  "Format ask session data into a markdown summary string. Pure."
  [{:keys [sessions unanswered errors empty-qs popular neg-fb
           missing-attrs quality-iss suggested-qs]}]
  (let [total (count sessions)]
    (if (zero? total)
      "No ask sessions recorded yet."
      (str "## Ask Session Insights (" total " sessions)\n\n"
           (when (seq unanswered)
             (str "### Unanswered questions (" (count unanswered) " sessions hit budget limit)\n"
                  "These questions could not be answered — they represent gaps:\n"
                  (->> unanswered
                       (take 10)
                       (map #(str "  - \"" (:question %) "\" (" (:iterations %) " iterations)"))
                       (str/join "\n"))
                  "\n\n"))
           (when (seq empty-qs)
             (str "### Queries that returned zero results (" (count empty-qs) " occurrences)\n"
                  "The agent wrote these Datalog queries but got no data back — likely wrong attributes or missing data:\n"
                  (->> empty-qs
                       (take 10)
                       (map (fn [[question query-edn]]
                              (str "  - Q: \"" (subs question 0 (min 60 (count question)))
                                   "\" → " (subs query-edn 0 (min 80 (count query-edn))))))
                       (str/join "\n"))
                  "\n\n"))
           (when (seq errors)
             (str "### Parse errors (" (count errors) " occurrences)\n"
                  "The LLM produced unparseable responses for these questions:\n"
                  (->> errors (take 5)
                       (map (fn [[question _]] (str "  - \"" question "\"")))
                       (str/join "\n"))
                  "\n\n"))
           (when (seq neg-fb)
             (str "### Negatively rated answers (" (count neg-fb) " — HIGHEST PRIORITY)\n"
                  "Users rated these answers unhelpful. The agent's own reflection on each session is included — use both signals to diagnose the root cause.\n"
                  (->> neg-fb
                       (take 10)
                       (map (fn [[question comment answer missing quality notes]]
                              (str "  - Q: \"" question "\"\n"
                                   "    Answer: " (subs answer 0 (min 100 (count answer)))
                                   (when (seq comment) (str "\n    User comment: \"" comment "\""))
                                   (when (seq missing) (str "\n    Agent said was MISSING: " missing))
                                   (when (seq quality) (str "\n    Agent flagged QUALITY issues: " quality))
                                   (when (seq notes)   (str "\n    Agent notes: " notes)))))
                       (str/join "\n"))
                  "\n\n"))
           (when (seq popular)
             (str "### Most common Datalog patterns (top 10)\n"
                  "These queries are written most often — consider making them named queries:\n"
                  (->> popular
                       (sort-by second >)
                       (take 10)
                       (map (fn [[qedn cnt]]
                              (str "  - (" cnt "x) " (subs qedn 0 (min 80 (count qedn))))))
                       (str/join "\n"))
                  "\n\n"))
           (summarize-feedback-items missing-attrs
                                     "Data Gaps Reported by Ask Agents"
                                     "Attributes or relationships agents needed but couldn't find:"
                                     "reported" 15)
           (summarize-feedback-items quality-iss
                                     "Data Quality Issues Reported by Agents"
                                     nil "reported" 10)
           (summarize-feedback-items suggested-qs
                                     "Named Queries Suggested by Agents"
                                     "Queries agents wished existed — consider adding these:"
                                     "suggested" 10)))))

(defn- ask-session-insights
  "Summarize ask session data for the introspect agent."
  [meta-db]
  (format-ask-insights (fetch-ask-data meta-db)))

;; --- Meta-prompt assembly ---

(defn- format-scores [results]
  (if (seq results)
    (->> results
         (map (fn [{:keys [id score reasoning]}]
                (str "  " (name id) ": " (name score)
                     (when reasoning (str " — " reasoning)))))
         (str/join "\n"))
    "No baseline scores yet."))

(defn- format-history [history]
  (if (seq history)
    (->> history
         (map-indexed
          (fn [i {:keys [target rationale outcome delta goal]}]
            (str "Iteration " (inc i)
                 ": target=" (if target (name target) "unknown")
                 " outcome=" (if outcome (name outcome) "unknown")
                 (when delta (str " delta=" (format "%+.3f" (double delta))))
                 (when goal (str " goal=\"" (util/truncate (str goal) 200) "\""))
                 "\n  " (util/truncate (or rationale "no rationale") 500))))
         (str/join "\n\n"))
    "No prior iterations."))

(defn- format-query-catalog [meta-db]
  (->> (artifacts/list-active-query-names meta-db)
       (keep (partial artifacts/load-named-query meta-db))
       (map (fn [{:keys [name description]}]
              (str "  " name " — " description)))
       (str/join "\n")))

(defn- architecture-summary
  "Summarize component topology for the introspect agent.
   Components live in the repo db, not meta-db."
  [db]
  (let [components (d/q '[:find ?name ?summary ?layer ?category ?complexity
                          :where
                          [?c :component/name ?name]
                          [(get-else $ ?c :component/summary "") ?summary]
                          [(get-else $ ?c :component/layer :unknown) ?layer]
                          [(get-else $ ?c :component/category :unknown) ?category]
                          [(get-else $ ?c :component/complexity :unknown) ?complexity]]
                        db)
        deps       (d/q '[:find ?from ?to
                          :where
                          [?c :component/name ?from]
                          [?c :component/depends-on ?d]
                          [?d :component/name ?to]]
                        db)
        file-counts (d/q '[:find ?name (count ?f)
                           :where
                           [?c :component/name ?name]
                           [?f :arch/component ?c]]
                         db)
        fc-map     (into {} file-counts)]
    (if (seq components)
      (str (count components) " components identified:\n"
           (->> components
                (sort-by first)
                (map (fn [[name summary layer category complexity]]
                       (str "  " name " [" (clojure.core/name layer) "/" (clojure.core/name category)
                            ", " (clojure.core/name complexity) ", " (get fc-map name 0) " files]"
                            (when (seq summary) (str " — " (subs summary 0 (min 120 (count summary))))))))
                (str/join "\n"))
           (when (seq deps)
             (str "\n\nDependency edges:\n"
                  (->> deps
                       (map (fn [[from to]] (str "  " from " → " to)))
                       (str/join "\n")))))
      "No components identified yet — run synthesize first.")))

(defn build-meta-prompt
  "Assemble the meta-prompt for the optimizer LLM.
   `db` is the repo database (for component data); `meta-db` is the shared meta database."
  [{:keys [db meta-db system-prompt examples rules history baseline-results]}]
  (let [template      (load-meta-prompt meta-db)
        total-queries (count (artifacts/list-active-query-names meta-db))
        base-mean     (if (seq baseline-results)
                        (let [scores (map (fn [{:keys [score]}]
                                            (case score :correct 1.0 :partial 0.5 0.0))
                                          baseline-results)]
                          (/ (apply + scores) (count scores)))
                        0.0)]
    (-> template
        (str/replace "{{current-system-prompt}}" (or system-prompt ""))
        (str/replace "{{current-examples}}" (pr-str (or examples [])))
        (str/replace "{{example-count}}" (str (count (or examples []))))
        (str/replace "{{total-queries}}" (str total-queries))
        (str/replace "{{all-queries}}" (format-query-catalog meta-db))
        (str/replace "{{current-rules}}" (pr-str (or rules [])))
        (str/replace "{{baseline-mean}}" (format "%.1f%%" (* 100.0 base-mean)))
        (str/replace "{{baseline-scores}}" (format-scores baseline-results))
        (str/replace "{{gap-analysis}}" (gap-analysis baseline-results))
        (str/replace "{{ask-insights}}" (ask-session-insights meta-db))
        (str/replace "{{architecture-summary}}" (architecture-summary (or db meta-db)))
        (str/replace "{{history}}" (format-history history)))))

;; --- Proposal parsing ---

(defn parse-proposal
  "Parse the optimizer LLM's response into a proposal map."
  [text]
  (when text
    (try
      (let [cleaned (analyze/strip-markdown-fences text)
            parsed  (edn/read-string {:readers {}} cleaned)]
        (when (map? parsed) parsed))
      (catch Exception e
        (log! (str "introspect: parse error: " (.getMessage e)))
        nil))))

(defn- valid-query-names [meta-db]
  (set (artifacts/list-active-query-names meta-db)))

(def ^:private valid-targets
  #{:examples :system-prompt :rules :code :train})

(def ^:private valid-train-keys
  #{:vocab-size :embedding-dim :hidden-dim :output-dim
    :learning-rate :batch-size :time-budget-sec
    :dropout :weight-decay})

(defn- validate-examples [modification query-names]
  (cond
    (not (vector? (:examples modification)))
    "For :examples target, :modification must contain {:examples [...]}"

    (some (complement query-names) (:examples modification))
    (str "Invalid query name(s) in :examples — valid: "
         (str/join ", " (sort query-names)))))

(defn- validate-system-prompt [modification]
  (cond
    (not (string? (:template modification)))
    "For :system-prompt target, :modification must contain {:template \"...\"}"

    (not-every? #(str/includes? (:template modification) %)
                ["{{repo-name}}" "{{schema}}" "{{rules}}" "{{examples}}"])
    "System prompt template must preserve all {{placeholders}}"))

(defn- validate-rules [modification]
  (cond
    (not (string? (:rules modification)))
    "For :rules target, :modification must contain {:rules \"...edn...\"}"

    (try (not (vector? (edn/read-string {:readers {}} (:rules modification))))
         (catch Exception _ true))
    "Rules must be a valid EDN vector"))

(defn- validate-code [modification]
  (cond
    (not (string? (:file modification)))
    "Code modification must include :file (string path)"

    (not (str/starts-with? (:file modification "") "src/noumenon/"))
    "Code modifications restricted to src/noumenon/ directory"

    (str/includes? (str (:file modification)) "..")
    "Code modification path must not contain '..'"

    (not (str/ends-with? (:file modification "") ".clj"))
    "Code modifications restricted to .clj files"

    (not (string? (:content modification)))
    "Code modification must include :content (string)"))

(defn- validate-train [modification]
  (if-not (map? (:config modification))
    "For :train target, :modification must contain {:config {...}}"
    (when-let [unknown (seq (remove valid-train-keys (keys (:config modification))))]
      (str "Unknown train config keys: " (str/join ", " (map name unknown))))))

(defn validate-proposal
  "Validate a parsed proposal. Returns nil if valid, error string if not."
  [meta-db proposal]
  (let [{:keys [target modification rationale]} proposal]
    (cond
      (not (valid-targets target))
      "Invalid :target — must be :examples, :system-prompt, :rules, :code, or :train"

      (not (string? rationale))
      "Missing or invalid :rationale"

      :else
      (case target
        :examples      (validate-examples modification (valid-query-names meta-db))
        :system-prompt (validate-system-prompt modification)
        :rules         (validate-rules modification)
        :code          (validate-code modification)
        :train         (validate-train modification)))))

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

(defmethod apply-modification! :examples [{:keys [meta-conn modification]}]
  (let [meta-db (d/db meta-conn)
        orig    (artifacts/load-prompt meta-db "agent-examples")]
    (artifacts/save-prompt! meta-conn "agent-examples"
                            (pr-str (:examples modification)) :introspect)
    orig))

(defmethod revert-modification! :examples [{:keys [meta-conn]} original]
  (artifacts/save-prompt! meta-conn "agent-examples" original :introspect))

(defmethod apply-modification! :system-prompt [{:keys [meta-conn modification]}]
  (let [meta-db (d/db meta-conn)
        orig    (artifacts/load-prompt meta-db "agent-system")]
    (artifacts/save-prompt! meta-conn "agent-system"
                            (:template modification) :introspect)
    orig))

(defmethod revert-modification! :system-prompt [{:keys [meta-conn]} original]
  (artifacts/save-prompt! meta-conn "agent-system" original :introspect))

(defmethod apply-modification! :rules [{:keys [meta-conn modification]}]
  (let [meta-db (d/db meta-conn)
        orig    (pr-str (artifacts/load-rules meta-db))]
    (artifacts/save-rules! meta-conn (:rules modification) :introspect)
    orig))

(defmethod revert-modification! :rules [{:keys [meta-conn]} original]
  (artifacts/save-rules! meta-conn original :introspect))

(defn- project-root
  "Return the project root directory, preferring the noumenon.project.dir
   system property over user.dir."
  []
  (System/getProperty "noumenon.project.dir" (System/getProperty "user.dir")))

(defn- validate-code-path!
  "Resolve canonical path and ensure it stays within src/noumenon/.
   Anchors to noumenon.project.dir (or user.dir) so the confinement
   boundary is stable regardless of JVM CWD."
  [file]
  (let [canonical (.getCanonicalPath (io/file file))
        src-dir   (.getCanonicalPath (io/file (project-root) "src/noumenon/"))]
    (when-not (str/starts-with? canonical (str src-dir "/"))
      (throw (ex-info "Code path escapes src/noumenon/" {:file file :canonical canonical})))
    canonical))

(defmethod apply-modification! :code [{:keys [modification]}]
  (let [{:keys [file content]} modification
        canonical (validate-code-path! file)
        orig      (when (.exists (io/file canonical)) (slurp canonical))]
    (spit canonical content)
    {:original orig :canonical canonical}))

(defmethod revert-modification! :code [{:keys [modification]} {:keys [original canonical]}]
  (let [path (or canonical (validate-code-path! (:file modification)))]
    (if original
      (spit path original)
      (let [f (io/file path)]
        (when (.exists f) (.delete f))))))

(def ^:private model-weights-path "data/models/latest.edn")

(defmethod apply-modification! :train [{:keys [modification]}]
  (let [config-orig  (save-raw "model/config.edn")
        weights-file (io/file model-weights-path)
        weights-orig (when (.exists weights-file) (slurp weights-file))]
    (spit (resource-path "model/config.edn")
          (pr-str (merge (model/load-config) (:config modification))))
    {:config-original  config-orig
     :weights-original weights-orig}))

(defmethod revert-modification! :train [_ {:keys [config-original weights-original]}]
  (spit (resource-path "model/config.edn") config-original)
  (let [wf (io/file model-weights-path)]
    (if weights-original
      (spit wf weights-original)
      (when (.exists wf) (.delete wf)))))

;; --- with-modification macro ---

(defmacro with-modification
  "Apply a modification, execute body, revert on :revert result or exception.
   Body must return a map with :outcome.
   If outcome is not :improved, reverts automatically."
  [proposal & body]
  `(let [proposal# ~proposal
         original# (apply-modification! proposal#)]
     (try
       (let [result# (do ~@body)]
         (when-not (= :improved (:outcome result#))
           (revert-modification! proposal# original#))
         result#)
       (catch Exception e#
         (log! (str "introspect: ERROR, reverting: " (.getMessage e#)))
         (revert-modification! proposal# original#)
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

(defn- run-code-gate!
  "Verify a code modification: syntax check (in-process read, no eval) then
   subprocess lint on a temp staging file. Never loads or executes LLM-written
   code in this process. Returns {:pass? bool :error string?}."
  [staging-path content]
  (if-let [syntax-err (verify-code-syntax content)]
    (do (log! (str "introspect: syntax check FAILED: " syntax-err))
        {:pass? false :error syntax-err})
    (let [staging (io/file staging-path)]
      (try
        (spit staging content)
        (let [{:keys [exit err]} (shell/sh "clj" "-M:lint" "--" staging-path)]
          (if (zero? exit)
            {:pass? true}
            (do (log! "introspect: lint FAILED")
                {:pass? false :error (str "Lint: " (subs (str err) 0 (min 200 (count (str err)))))})))
        (finally
          (when (.exists staging) (.delete staging)))))))

;; --- Git commit ---

(def ^:private committable-paths
  ["resources/prompts/" "resources/queries/" "resources/model/" "src/noumenon/"])

(defn- sanitize-commit-text
  "Strip newlines and limit length to prevent commit message injection."
  [s max-len]
  (-> (str s)
      (str/replace #"[\n\r]" " ")
      (subs 0 (min max-len (count (str s))))))

(defn- sh-checked!
  "Run shell/sh; throw on non-zero exit with stderr context."
  [& args]
  (let [{:keys [exit err] :as result} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "Shell command failed (exit " exit "): " (first args))
                      {:args args :exit exit :err err})))
    result))

(defn- git-commit-improvement! [repo-path {:keys [target rationale delta]}]
  (let [safe-rationale (sanitize-commit-text rationale 200)
        msg (str "introspect(" (name target) "): " safe-rationale
                 (when delta (str " [" (format "%+.3f" (double delta)) "]")))]
    (log! (str "introspect: committing: " msg))
    (doseq [p committable-paths]
      (sh-checked! "git" "-C" (str repo-path) "add" p))
    (sh-checked! "git" "-C" (str repo-path) "commit" "-m" msg)))

;; --- Agent-mode evaluation ---

(defn- score-kw->num [kw]
  (case kw :correct 1.0 :partial 0.5 :wrong 0.0 0.0))

(defn- evaluate-once!
  "Run one evaluation pass. Returns {:mean double :results [...] :total-iterations long}"
  [meta-db db repo-name invoke-fn-factory questions]
  (let [total       (count questions)
        ask-results (mapv (fn [idx q]
                            (let [t0    (System/currentTimeMillis)
                                  _     (log! (str "  eval [" (inc idx) "/" total "] "
                                                   (name (:id q))))
                                  ask-r (agent/ask meta-db db (:question q)
                                                   {:invoke-fn      (invoke-fn-factory)
                                                    :repo-name      repo-name
                                                    :max-iterations 6})
                                  {:keys [score reasoning]}
                                  (bench/deterministic-score q meta-db db (or (:answer ask-r) ""))
                                  elapsed (/ (- (System/currentTimeMillis) t0) 1000.0)]
                              (log! (str "  eval [" (inc idx) "/" total "] "
                                         (name (:id q)) " — " (format "%.1fs" elapsed)
                                         " " (name score)))
                              {:id (:id q) :score score :reasoning reasoning
                               :iterations (get-in ask-r [:usage :iterations] 0)}))
                          (range) questions)
        scores          (mapv (comp score-kw->num :score) ask-results)
        total-iters     (apply + (map :iterations ask-results))]
    {:mean             (if (seq scores) (/ (apply + scores) (count scores)) 0.0)
     :results          (mapv #(dissoc % :iterations) ask-results)
     :total-iterations total-iters}))

(defn- median [nums]
  (let [sorted (sort nums)
        n      (count sorted)]
    (if (odd? n)
      (nth sorted (quot n 2))
      (/ (+ (nth sorted (dec (quot n 2)))
            (nth sorted (quot n 2)))
         2.0))))

(defn- evaluate-repo!
  "Evaluate against a single repo. Handles eval-runs for variance reduction."
  [meta-db db repo-name invoke-fn-factory eval-runs]
  (let [targets   (bench/pick-benchmark-targets meta-db db)
        questions (filterv #(= :deterministic (:scoring %))
                           (bench/resolve-question-params
                            (bench/load-questions) targets))]
    (if (<= eval-runs 1)
      (evaluate-once! meta-db db repo-name invoke-fn-factory questions)
      (let [runs (mapv (fn [i]
                         (log! (str "  eval pass " (inc i) "/" eval-runs))
                         (evaluate-once! meta-db db repo-name invoke-fn-factory questions))
                       (range eval-runs))
            med  (median (mapv :mean runs))
            best (apply min-key #(Math/abs (- (:mean %) med)) runs)]
        (log! (str "  median of " eval-runs " runs: " (format "%.1f%%" (* 100.0 med))))
        best))))

(defn evaluate-agent!
  "Evaluate agent performance on deterministic benchmark questions.
   Supports multi-repo: pass :extra-repos [{:db db :repo-name name}...]
   to evaluate across multiple repos and average the scores.
   Returns {:mean double :results [...]}."
  [meta-db db repo-name invoke-fn-factory & {:keys [eval-runs extra-repos]
                                             :or   {eval-runs 1}}]
  (let [primary (do (log! (str "  evaluating " repo-name))
                    (evaluate-repo! meta-db db repo-name invoke-fn-factory eval-runs))
        extras  (mapv (fn [{:keys [db repo-name]}]
                        (log! (str "  evaluating " repo-name))
                        (evaluate-repo! meta-db db repo-name invoke-fn-factory eval-runs))
                      (or extra-repos []))
        all     (into [primary] extras)]
    (if (= 1 (count all))
      primary
      (let [agg-mean (/ (apply + (map :mean all)) (count all))]
        (log! (str "  aggregate mean across " (count all) " repos: "
                   (format "%.1f%%" (* 100.0 agg-mean))))
        {:mean             agg-mean
         :results          (:results primary) ;; per-question detail from primary repo
         :repo-means       (mapv :mean all)
         :total-iterations (apply + (map #(or (:total-iterations %) 0) all))}))))

;; --- Datomic transaction builders (pure) ---

(def ^:private max-modification-bytes
  "Max size for serialized modification stored in Datomic.
   Datomic dev-local has a ~100KB per-value limit; leave headroom."
  65536)

(defn- truncate-modification
  "Serialize a modification map to EDN, truncating to max-modification-bytes.
   For :code targets the full content lives on disk / in git, so a truncated
   copy in Datomic is acceptable."
  [modification]
  (let [s (pr-str modification)]
    (if (<= (count s) max-modification-bytes)
      s
      (str (subs s 0 max-modification-bytes) "…[truncated]"))))

(defn- iter->tx-data [index {:keys [target goal rationale outcome baseline
                                    result delta modification error]}]
  (let [base {:introspect.iter/index     (inc (long index))
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
      (assoc :introspect.iter/modification (truncate-modification modification)))))

(defn- run-start-tx-data
  "Build tx-data to create the run entity at the start of introspection."
  [{:keys [run-id repo-path commit-sha started-at model-config
           max-iterations prompt-hash examples-hash rules-hash
           db-basis-t baseline-mean]}]
  (let [base {:introspect.run/id              run-id
              :introspect.run/repo-path       (str repo-path)
              :introspect.run/started-at      (or started-at (java.util.Date.))
              :introspect.run/iteration-count 0
              :introspect.run/improvement-count 0}]
    [(cond-> base
       commit-sha     (assoc :introspect.run/commit-sha commit-sha)
       model-config   (assoc :introspect.run/model-config (pr-str model-config))
       max-iterations (assoc :introspect.run/max-iterations (long max-iterations))
       prompt-hash    (assoc :introspect.run/prompt-hash prompt-hash)
       examples-hash  (assoc :introspect.run/examples-hash examples-hash)
       rules-hash     (assoc :introspect.run/rules-hash rules-hash)
       db-basis-t     (assoc :introspect.run/db-basis-t (long db-basis-t))
       baseline-mean  (assoc :introspect.run/baseline-mean (double baseline-mean)))
     {:db/id "datomic.tx" :tx/op :introspect}]))

(defn- iter-tx-data
  "Build tx-data to persist a single iteration and attach it to the run."
  [run-id index record]
  (let [iter-entity (iter->tx-data index record)]
    [{:introspect.run/id         run-id
      :introspect.run/iterations [iter-entity]}
     {:db/id "datomic.tx" :tx/op :introspect}]))

(defn- run-complete-tx-data
  "Build tx-data to finalize the run entity with completion stats."
  [{:keys [run-id started-at final-mean iteration-count
           improvement-count cost-usd]}]
  [{:introspect.run/id              run-id
    :introspect.run/completed-at    (java.util.Date.)
    :introspect.run/duration-ms     (long (- (System/currentTimeMillis)
                                             (.getTime ^java.util.Date
                                              (or started-at (java.util.Date.)))))
    :introspect.run/iteration-count (long iteration-count)
    :introspect.run/improvement-count (long improvement-count)
    :introspect.run/final-mean      (double (or final-mean 0.0))
    :introspect.run/cost-usd        (double (or cost-usd 0.0))}
   {:db/id "datomic.tx" :tx/op :introspect}])

;; --- Iteration ---

(defn- make-record [proposal outcome & {:as extra}]
  (merge {:target    (:target proposal)
          :goal      (:goal proposal)
          :rationale (:rationale proposal)
          :outcome   outcome}
         extra))

(defn- classify-proposal
  "Classify a proposal as :skip (with reason) or nil if valid to proceed."
  [proposal validation allowed-targets]
  (cond
    (nil? proposal)
    {:outcome :skipped :record {:outcome :skipped :rationale "Parse failure"}
     :message "introspect: failed to parse proposal, skipping"}

    validation
    {:outcome :skipped :record {:outcome :skipped :rationale (str "Validation: " validation)}
     :message (str "introspect: invalid proposal: " validation)}

    (and allowed-targets (not (allowed-targets (:target proposal))))
    (let [t (:target proposal)]
      {:outcome :skipped
       :record  {:outcome :skipped :rationale (str "Target " (name t) " not allowed")}
       :message (str "introspect: target " (name t) " not in allowed set "
                     (pr-str allowed-targets) ", skipping")})))

(defn- apply-train-model!
  "Train the priority model when target is :train."
  [meta-conn db]
  (let [config  (model/load-config)
        dataset (td/build-dataset (d/db meta-conn) db config)
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
    (model/save-model! (assoc mdl
                              :vocab (:vocab dataset)
                              :label-index (:label-index dataset))
                       "data/models/latest.edn")))

(defn- evaluate-and-decide
  "Evaluate after modification and decide whether to keep or revert.
   Returns {:outcome kw :record map :eval-result map?}."
  [{:keys [meta-conn db repo-name repo-path invoke-fn-factory baseline
           git-commit? eval-runs]} proposal]
  (log! "introspect: evaluating...")
  (let [eval-result   (evaluate-agent! (d/db meta-conn) db repo-name invoke-fn-factory
                                       :eval-runs (or eval-runs 1))
        new-mean      (:mean eval-result)
        base-mean     (:mean baseline)
        delta         (- new-mean base-mean)
        base-iters    (or (:total-iterations baseline) 0)
        new-iters     (or (:total-iterations eval-result) 0)
        iter-increase (if (pos? base-iters)
                        (/ (double (- new-iters base-iters)) base-iters)
                        0.0)
        improved?     (and (> delta 0.001) (< iter-increase 0.5))]
    (when (and (> delta 0.001) (>= iter-increase 0.5))
      (log! (str "introspect: accuracy improved but iteration cost increased "
                 (format "%.0f%%" (* 100 iter-increase)) " — rejecting")))
    (if improved?
      (do (log! (str "introspect: IMPROVED " (format "%+.1f%%" (* 100 delta))
                     " (" (format "%.1f%%" (* 100 base-mean))
                     " -> " (format "%.1f%%" (* 100 new-mean)) ")"))
          (when git-commit?
            (git-commit-improvement! repo-path
                                     {:target (:target proposal) :rationale (:rationale proposal)
                                      :delta delta}))
          {:outcome     :improved
           :record      (make-record proposal :improved
                                     :baseline base-mean :result new-mean
                                     :delta delta :modification (:modification proposal))
           :eval-result eval-result})
      (do (log! (str "introspect: reverted (delta=" (format "%+.1f%%" (* 100 delta)) ")"))
          {:outcome :reverted
           :record  (make-record proposal :reverted
                                 :baseline base-mean :result new-mean
                                 :delta delta)}))))

(defn run-iteration!
  "Run a single introspect iteration.
   Returns {:outcome kw :record map :eval-result map?}."
  [{:keys [db meta-db meta-conn optimizer-invoke-fn
           baseline history allowed-targets] :as ctx}]
  (let [meta-prompt (build-meta-prompt
                     {:db               db
                      :meta-db          meta-db
                      :system-prompt    (load-current-system-prompt meta-db)
                      :examples         (load-current-examples meta-db)
                      :rules            (load-current-rules meta-db)
                      :history          history
                      :baseline-results (:results baseline)})
        response    (try
                      (log! "introspect: requesting proposal from optimizer...")
                      (optimizer-invoke-fn [{:role "user" :content meta-prompt}])
                      (catch Exception e
                        (log! (str "introspect: optimizer error: " (.getMessage e)))
                        nil))
        proposal    (parse-proposal (:text response))
        validation  (when proposal (validate-proposal meta-db proposal))]
    (if-let [{:keys [message] :as skip} (classify-proposal proposal validation allowed-targets)]
      (do (log! message) (dissoc skip :message))
      (let [{:keys [target modification]} proposal
            _ (log! (str "introspect: target=" (name target)
                         " goal=" (pr-str (:goal proposal)) "\n  " (:rationale proposal)))
            code-gate (when (= :code target)
                        (run-code-gate!
                         (str (validate-code-path! (:file modification)) ".staging")
                         (:content modification)))]
        (if (and (= :code target) (not (:pass? code-gate)))
          {:outcome :gate-failed :record (make-record proposal :gate-failed)}
          (with-modification (assoc proposal :meta-conn meta-conn)
            (when (= :train target)
              (apply-train-model! meta-conn db))
            (evaluate-and-decide ctx proposal)))))))

;; --- Main loop ---

(defn- initialize-run!
  "Set up a run: compute hashes, evaluate baseline, persist run entity.
   Returns {:run-id :baseline :history :start-ms :started-at :max-ms}."
  [{:keys [db repo-name repo-path invoke-fn-factory meta-conn
           max-hours max-cost model-config max-iterations eval-runs run-id]}]
  (let [run-id     (or run-id (generate-run-id))
        start-ms   (System/currentTimeMillis)
        started-at (java.util.Date.)
        max-ms     (when max-hours (* max-hours 3600000))
        meta-db    (d/db meta-conn)
        history    (load-history meta-db)
        hashes     {:prompt-hash   (util/sha256-hex (or (load-current-system-prompt meta-db) ""))
                    :examples-hash (util/sha256-hex (pr-str (or (load-current-examples meta-db) [])))
                    :rules-hash    (util/sha256-hex (pr-str (or (load-current-rules meta-db) [])))}
        commit-sha (git/head-sha repo-path)
        db-basis-t (try (:t (d/db-stats db)) (catch Exception _ nil))
        _          (log! "introspect: running baseline evaluation...")
        baseline   (evaluate-agent! meta-db db repo-name invoke-fn-factory
                                    :eval-runs eval-runs)
        _          (log! (str "introspect: baseline mean="
                              (format "%.1f%%" (* 100.0 (:mean baseline)))))
        _          (when max-cost
                     (log! "introspect: WARNING: max-cost budget is not yet tracked (no LLM cost data available)"))]
    (d/transact meta-conn
                {:tx-data (run-start-tx-data
                           (merge hashes
                                  {:run-id run-id :repo-path repo-path
                                   :commit-sha commit-sha :started-at started-at
                                   :model-config model-config
                                   :max-iterations max-iterations
                                   :db-basis-t db-basis-t
                                   :baseline-mean (:mean baseline)}))})
    (log! (str "introspect: created run " run-id))
    {:run-id run-id :baseline baseline :history history
     :start-ms start-ms :started-at started-at :max-ms max-ms}))

(defn- budget-done?
  "Return a reason string if the budget is exhausted, nil otherwise."
  [{:keys [stop-flag max-iterations max-ms start-ms]} i]
  (cond
    (and stop-flag @stop-flag)          "stopped by request"
    (>= i max-iterations)              (str "reached max iterations (" max-iterations ")")
    (and max-ms (> (- (System/currentTimeMillis) start-ms) max-ms)) "time budget exhausted"))

(defn run-loop!
  "Run the introspect improvement loop.
   Persists results to the internal meta database.
   Returns {:run-id str :iterations n :improvements n :final-score double}."
  [{:keys [db repo-name repo-path invoke-fn-factory optimizer-invoke-fn
           meta-conn max-iterations git-commit? allowed-targets eval-runs
           stop-flag progress-fn]
    :or   {max-iterations 10 eval-runs 1}
    :as   opts}]
  (let [{:keys [run-id baseline history start-ms started-at max-ms]}
        (initialize-run! opts)

        budget  {:stop-flag stop-flag :max-iterations max-iterations
                 :max-ms max-ms :start-ms start-ms}

        result
        (loop [i 0, baseline baseline, history history, improvements 0]
          (if-let [reason (budget-done? budget i)]
            (do (log! (str "introspect: " reason))
                {:iterations i :improvements improvements
                 :final-score (:mean baseline)})
            (do (log! (str "\nintrospect: === Iteration " (inc i) "/"
                           max-iterations " ==="))
                (let [{:keys [outcome eval-result record]}
                      (run-iteration!
                       {:db db :meta-db (d/db meta-conn) :meta-conn meta-conn
                        :repo-name repo-name :repo-path repo-path
                        :invoke-fn-factory invoke-fn-factory
                        :optimizer-invoke-fn optimizer-invoke-fn
                        :baseline baseline :history history
                        :git-commit? git-commit?
                        :allowed-targets allowed-targets
                        :eval-runs eval-runs})
                      new-baseline (if (= :improved outcome) eval-result baseline)]
                  (d/transact meta-conn
                              {:tx-data (iter-tx-data run-id i record)})
                  (when progress-fn
                    (progress-fn {:current (inc i) :total max-iterations
                                  :message (str (name (or (:target record) :unknown))
                                                " " (name (or outcome :unknown)))}))
                  (recur (inc i) new-baseline (conj history record)
                         (if (= :improved outcome) (inc improvements) improvements))))))]
    (d/transact meta-conn
                {:tx-data (run-complete-tx-data
                           {:run-id run-id :started-at started-at
                            :final-mean (:final-score result)
                            :iteration-count (:iterations result)
                            :improvement-count (:improvements result)
                            :cost-usd 0.0})})
    (log! (str "introspect: completed run " run-id))
    (assoc result :run-id run-id)))
