(ns noumenon.benchmark
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [noumenon.llm :as llm]
            [noumenon.query :as query])
  (:import [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

;; --- Loading ---

(defn load-questions
  "Load benchmark questions from classpath."
  []
  (-> (io/resource "benchmark/questions.edn") slurp edn/read-string))

(defn load-rubric
  "Load scoring rubric and judge template from classpath."
  []
  (-> (io/resource "benchmark/rubric.edn") slurp edn/read-string))

;; --- Canary ---

(def canary-question-ids
  "Question IDs used for canary phase: cheapest single-hop with deterministic scoring."
  #{:q01 :q02})

(defn canary-evaluate
  "Evaluate canary results. Takes a seq of result maps for canary questions.
   Returns {:status :pass/:warn :details [...]}."
  [canary-results]
  (let [scores (mapv (fn [r] {:id (:id r) :score (:query-score r)}) canary-results)
        all-wrong? (every? (comp #{:wrong} :score) scores)]
    (if all-wrong?
      {:status :warn :details scores}
      {:status :pass :details scores})))

;; --- Context assembly ---

(defn query-context
  "Build context string from a named query's results for the query-augmented condition."
  [db query-name]
  (let [{:keys [ok error]} (query/run-named-query db query-name)]
    (if error
      (str "Query error: " error)
      (pr-str ok))))

(defn raw-context
  "Concatenate all source files from repo-path as context for the raw condition.
   Reads files from git HEAD."
  [repo-path]
  (let [{:keys [exit out err]} (shell/sh "git" "-C" (str repo-path)
                                         "ls-tree" "-r" "--name-only" "HEAD")]
    (when (not= 0 exit)
      (throw (ex-info (str "git ls-tree failed: " (str/trim (or err "")))
                      {:exit exit})))
    (let [source-ext? (fn [path]
                        (some #(str/ends-with? path %)
                              [".clj" ".cljs" ".cljc" ".java"]))]
      (->> (str/split-lines out)
           (remove str/blank?)
           (filter source-ext?)
           (map (fn [path]
                  (let [{:keys [out]} (shell/sh "git" "-C" (str repo-path)
                                                "show" (str "HEAD:" path))]
                    (str "--- " path " ---\n" out "\n"))))
           (str/join "\n")))))

;; --- Prompts ---

(defn answer-prompt
  "Build a prompt for answering a benchmark question with given context."
  [question context]
  (str "You are answering a question about the Ring Clojure library.\n\n"
       "Context:\n" context "\n\n"
       "Question: " question "\n\n"
       "Provide a detailed, accurate answer based on the context provided."))

(defn judge-prompt
  "Build a judge prompt from the rubric template."
  [template question rubric answer]
  (-> template
      (str/replace "{{question}}" question)
      (str/replace "{{rubric}}" rubric)
      (str/replace "{{answer}}" answer)))

;; --- Deterministic scoring ---

(defmulti deterministic-score
  "Score a single-hop question deterministically using Datalog ground truth.
   Dispatches on question :id. Returns {:score kw :reasoning str}."
  (fn [question _db _answer-text] (:id question)))

(defmethod deterministic-score :q01
  [question db answer-text]
  (let [{:keys [ok]} (query/run-named-query db (:query-name question))
        complex-files (->> ok
                           (filter (fn [[_path complexity]]
                                     (#{:complex :very-complex} complexity)))
                           (map first)
                           set)
        found         (count (filter #(str/includes? answer-text %) complex-files))
        total         (count complex-files)
        ratio         (if (pos? total) (/ (double found) total) 0.0)]
    (cond
      (= found total)
      {:score :correct :reasoning (str "All " total " complex files listed")}

      (>= ratio 0.5)
      {:score :partial :reasoning (str found "/" total " complex files listed (≥50%)")}

      :else
      {:score :wrong :reasoning (str found "/" total " complex files listed (<50%)")})))

(defmethod deterministic-score :q02
  [question db answer-text]
  (let [{:keys [ok]} (query/run-named-query db (:query-name question))
        target-file  "ring/middleware/params.clj"
        layer        (->> ok
                          (filter (fn [[path _]] (str/ends-with? path target-file)))
                          first
                          second)]
    (if (and layer (str/includes? answer-text (name layer)))
      {:score :correct :reasoning (str "Correct layer: " (name layer))}
      {:score :wrong :reasoning (str "Expected layer " (when layer (name layer))
                                     " not found in answer")})))

(defmethod deterministic-score :q03
  [question db answer-text]
  (let [{:keys [ok]} (query/run-named-query db (:query-name question))
        ranked       (->> ok
                          (sort-by (fn [[_ _ cnt]] cnt) #(compare %2 %1))
                          (take 3)
                          (mapv first))
        found        (filterv #(str/includes? answer-text %) ranked)
        ;; Check order: each found name appears after the previous one
        in-order?    (let [positions (mapv #(str/index-of answer-text %) found)]
                       (= positions (sort positions)))]
    (cond
      (and (= 3 (count found)) in-order?)
      {:score :correct :reasoning (str "All 3 contributors in rank order: " (str/join ", " ranked))}

      (>= (count found) 2)
      {:score :partial :reasoning (str (count found) "/3 contributors found")}

      :else
      {:score :wrong :reasoning (str (count found) "/3 contributors found")})))

;; --- Scoring ---

(def ^:private score-values
  {:correct 1.0 :partial 0.5 :wrong 0.0})

(defn parse-judge-response
  "Parse judge LLM response into {:score keyword :reasoning string}.
   Returns nil on parse failure."
  [raw]
  (when-not (str/blank? raw)
    (let [try-parse (fn [s]
                      (try
                        (let [result (edn/read-string s)]
                          (when (and (map? result) (contains? score-values (:score result)))
                            result))
                        (catch Exception _ nil)))]
      (or (try-parse (str/trim raw))
          (try-parse (-> raw str/trim
                         (str/replace #"^```\w*\n?" "")
                         (str/replace #"\n?```$" "")
                         str/trim))))))

(defn score-value
  "Convert a score keyword to its numeric value."
  [score-kw]
  (get score-values score-kw 0.0))

(defn aggregate-scores
  "Compute aggregate statistics from a seq of result maps.
   Each result has :query-score and optionally :raw-score as keywords.
   When mode is provided, sets :canonical flag."
  ([results] (aggregate-scores results nil))
  ([results mode]
   (let [q-scores   (mapv #(score-value (:query-score %)) results)
         has-raw?   (some :raw-score results)
         r-scores   (when has-raw? (mapv #(score-value (:raw-score %)) results))
         mean       (fn [xs] (if (seq xs) (/ (reduce + xs) (count xs)) 0.0))
         by-cat     (group-by :category results)
         canonical? (not (or (:skip-raw mode) (:skip-judge mode)))]
     (cond-> {:question-count (count results)
              :query-mean     (mean q-scores)
              :canonical      canonical?
              :per-category   (into {}
                                    (map (fn [[cat rs]]
                                           [cat (cond-> {:query-mean (mean (mapv #(score-value (:query-score %)) rs))
                                                         :count      (count rs)}
                                                  has-raw?
                                                  (assoc :raw-mean (mean (mapv #(score-value (:raw-score %)) rs))))]))
                                    by-cat)}
       has-raw? (assoc :raw-mean (mean r-scores))))))

;; --- Usage tracking ---

(defn aggregate-usage
  "Sum usage maps from all completed stages. Returns a usage map."
  [stages]
  (reduce llm/sum-usage llm/zero-usage (keep :usage (vals stages))))

;; --- Checkpoint I/O ---

(defn generate-run-id
  "Generate a run ID: <timestamp-ms>-<4-hex-chars>."
  []
  (str (System/currentTimeMillis) "-" (format "%04x" (rand-int 0x10000))))

(defn question-set-hash
  "SHA-256 hex digest of questions EDN for compatibility checking."
  [questions]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes (pr-str questions) "UTF-8"))]
    (str/join (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn repo-head-sha
  "Get the HEAD commit SHA for a repository."
  [repo-path]
  (let [{:keys [exit out]} (shell/sh "git" "-C" (str repo-path) "rev-parse" "HEAD")]
    (when (= 0 exit)
      (str/trim out))))

(defn checkpoint-write
  "Atomically write checkpoint EDN to path (write temp + atomic rename).
   Uses thread-specific temp file name to avoid collisions under concurrency."
  [path checkpoint]
  (let [f   (io/file path)
        tid (.getId (Thread/currentThread))
        tmp (io/file (str path ".tmp-" tid))]
    (.mkdirs (.getParentFile f))
    (spit tmp (pr-str checkpoint))
    (Files/move (.toPath tmp) (.toPath f)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    path))

(def ^:private checkpoint-lock (Object.))

(defn checkpoint-write-latest!
  "Thread-safe checkpoint write: serializes writes via locking and always writes
   the latest atom state. Use this from concurrent threads."
  [path checkpoint-atom]
  (locking checkpoint-lock
    (checkpoint-write path @checkpoint-atom)))

(defn checkpoint-read
  "Read and parse checkpoint EDN from path."
  [path]
  (-> path slurp edn/read-string))

;; --- Resume ---

(defn validate-resume-compatibility
  "Compare checkpoint metadata against current config.
   Required fields always checked. Optional fields (prompt hashes) only checked
   when both sides are non-nil — old checkpoints without hashes remain compatible.
   Returns {:ok true} if compatible, {:mismatches [...]} with field details otherwise."
  [checkpoint current-config]
  (let [required   [:repo-path :commit-sha :question-set-hash :model-config :mode]
        optional   [:rubric-hash :answer-prompt-hash]
        cp-meta    (:metadata checkpoint)
        req-mm     (for [field required
                         :let [cp-val (get cp-meta field) cur-val (get current-config field)]
                         :when (not= cp-val cur-val)]
                     {:field field :checkpoint cp-val :current cur-val})
        opt-mm     (for [field optional
                         :let [cp-val (get cp-meta field) cur-val (get current-config field)]
                         :when (and (some? cp-val) (some? cur-val) (not= cp-val cur-val))]
                     {:field field :checkpoint cp-val :current cur-val})
        mismatches (vec (concat req-mm opt-mm))]
    (if (seq mismatches)
      {:mismatches mismatches}
      {:ok true})))

(defn find-checkpoint
  "Find a checkpoint file. `\"latest\"` returns the most recent by filename sort.
   A specific run-id returns that checkpoint. Returns path string or nil."
  [checkpoint-dir resume-arg]
  (let [dir (io/file checkpoint-dir)]
    (when (.isDirectory dir)
      (let [edn-files (->> (.listFiles dir)
                           (filter #(str/ends-with? (.getName %) ".edn"))
                           (remove #(str/ends-with? (.getName %) ".tmp"))
                           (sort-by #(.getName %) #(compare %2 %1)))]
        (if (= "latest" resume-arg)
          (when (seq edn-files)
            (str (first edn-files)))
          (let [target (str resume-arg ".edn")]
            (some #(when (= (.getName %) target) (str %)) edn-files)))))))

;; --- Budget ---

(defn budget-check
  "Check if budget is exhausted. Returns :ok or {:exhausted :max-questions/:max-cost/:stop-after}.
   `stages-completed` is count of completed stages.
   `session-cost-usd` is accumulated cost for this session only (excludes prior sessions on resume).
   `budget` is {:max-questions n :stop-after-ms ms :max-cost-usd d}.
   `stages-per-question` is stages per question (default 4, varies with mode flags)."
  ([stages-completed session-cost-usd budget started-at-ms]
   (budget-check stages-completed session-cost-usd budget started-at-ms 4))
  ([stages-completed session-cost-usd budget started-at-ms stages-per-question]
   (let [{:keys [max-questions max-cost-usd stop-after-ms]} budget]
     (cond
       (and max-questions (>= stages-completed (* stages-per-question max-questions)))
       {:exhausted :max-questions}

       (and max-cost-usd (> session-cost-usd max-cost-usd))
       {:exhausted :max-cost}

       (and stop-after-ms (>= (- (System/currentTimeMillis) started-at-ms) stop-after-ms))
       {:exhausted :stop-after}

       :else :ok))))

;; --- Stage execution ---

(defn stage-keys
  "Return the 4 ordered stage keys for a question: [id condition stage-type]."
  [question]
  (let [qid (:id question)]
    [[qid :query :answer]
     [qid :query :judge]
     [qid :raw :answer]
     [qid :raw :judge]]))

(defn mode-stage-keys
  "Return stage keys for a question filtered by mode.
   Mode map: {:skip-raw bool :skip-judge bool}.
   When :skip-judge is true, deterministic judge stages are kept."
  [question mode]
  (let [qid         (:id question)
        skip-raw?   (:skip-raw mode)
        skip-judge? (:skip-judge mode)
        deterministic? (= :deterministic (:scoring question))]
    (cond-> []
      true                                  (conj [qid :query :answer])
      (or (not skip-judge?) deterministic?) (conj [qid :query :judge])
      (not skip-raw?)                       (conj [qid :raw :answer])
      (and (not skip-raw?)
           (or (not skip-judge?) deterministic?)) (conj [qid :raw :judge]))))

(defn all-stage-keys
  "Return all stage keys for all questions in execution order.
   When mode is provided, filters stages by skip flags."
  ([questions] (vec (mapcat stage-keys questions)))
  ([questions mode]
   (if (or (:skip-raw mode) (:skip-judge mode))
     (vec (mapcat #(mode-stage-keys % mode) questions))
     (vec (mapcat stage-keys questions)))))

(defn run-stage
  "Execute a single benchmark stage. Returns {:status :ok :result ... :usage ... :completed-at ...}.
   `invoke-llm` is (prompt -> {:text string :usage map}).
   `stages` is the map of already-completed stages (judge stages read their answer from it).
   `raw-ctx` is the precomputed raw-context string."
  [stage-key question rubric-map db raw-ctx stages invoke-llm judge-llm]
  (let [[qid condition stage-type] stage-key
        q-text  (:question question)
        deterministic? (and (= :judge stage-type)
                            (= :deterministic (:scoring question)))]
    (if deterministic?
      ;; Deterministic scoring — no LLM call
      (let [answer (get-in stages [[qid condition :answer] :result])
            score  (deterministic-score question db answer)]
        (binding [*out* *err*]
          (println (str "bench/deterministic-score q=" (name qid)
                        " condition=" (name condition)
                        " score=" (name (:score score)))))
        {:status :ok :result score :usage llm/zero-usage :resolved-model nil
         :completed-at (java.util.Date.)})
      ;; LLM scoring / answering
      (let [llm-fn  (if (= :judge stage-type) judge-llm invoke-llm)
            {:keys [text usage resolved-model]}
            (case [condition stage-type]
              [:query :answer]
              (llm-fn (answer-prompt q-text (query-context db (:query-name question))))

              [:query :judge]
              (let [answer (get-in stages [[qid :query :answer] :result])]
                (llm-fn (judge-prompt (:judge-template rubric-map)
                                      q-text (:rubric question) answer)))

              [:raw :answer]
              (llm-fn (answer-prompt q-text raw-ctx))

              [:raw :judge]
              (let [answer (get-in stages [[qid :raw :answer] :result])]
                (llm-fn (judge-prompt (:judge-template rubric-map)
                                      q-text (:rubric question) answer))))
            result  (if (= :judge stage-type)
                      (parse-judge-response text)
                      text)]
        {:status :ok :result result :usage usage :resolved-model resolved-model
         :completed-at (java.util.Date.)}))))

(defn stages->results
  "Convert stages map to result seq. Only includes questions with all expected stages complete.
   When mode is provided, adjusts expected stages accordingly."
  ([questions stages] (stages->results questions stages nil))
  ([questions stages mode]
   (for [q questions
         :let [qid       (:id q)
               exp-keys  (if mode (mode-stage-keys q mode) (stage-keys q))]
         :when (every? #(contains? stages %) exp-keys)
         :let [q-judge (get-in stages [[qid :query :judge] :result])
               r-judge (get-in stages [[qid :raw :judge] :result])]]
     (cond-> {:id              (:id q)
              :category        (:category q)
              :query-name      (:query-name q)
              :query-answer    (get-in stages [[qid :query :answer] :result])
              :query-score     (or (:score q-judge) :wrong)
              :query-reasoning (:reasoning q-judge)}
       (not (:skip-raw mode))
       (assoc :raw-answer   (get-in stages [[qid :raw :answer] :result])
              :raw-score    (or (:score r-judge) :wrong)
              :raw-reasoning (:reasoning r-judge))))))

;; --- Rate limiting ---

(defn acquire-rate-gate!
  "Block until min-delay-ms has elapsed since the last LLM request.
   Thread-safe via locking on the atom."
  [last-request-atom min-delay-ms]
  (when (pos? min-delay-ms)
    (locking last-request-atom
      (let [now     (System/currentTimeMillis)
            elapsed (- now @last-request-atom)
            wait    (- min-delay-ms elapsed)]
        (when (pos? wait)
          (Thread/sleep wait))
        (reset! last-request-atom (System/currentTimeMillis))))))

;; --- Pair execution ---

(defn- run-pair!
  "Run answer+judge for one question/condition pair. Checks stop-flag and budget
   before each stage. Acquires rate gate before each LLM call. Updates checkpoint
   atomically. On error, stores exception and sets stop-flag."
  [{:keys [qid condition question rubric-map db raw-ctx
           checkpoint cp-path invoke-llm judge-llm
           session-cost budget start-ms stop-flag error-atom
           rate-gate min-delay-ms run-id total stages-per-question
           stage-types]}]
  (try
    (doseq [stage-type stage-types]
      (let [stage-key [qid condition stage-type]]
        (cond
          (contains? (:stages @checkpoint) stage-key)
          (binding [*out* *err*]
            (println (str "bench/stage-skip run-id=" run-id
                          " q=" (name qid)
                          " condition=" (name condition)
                          " stage=" (name stage-type)
                          " reason=already-complete")))

          @stop-flag nil

          :else
          (let [b (budget-check (count (:stages @checkpoint)) @session-cost budget start-ms
                                stages-per-question)]
            (if (not= :ok b)
              (when (compare-and-set! stop-flag nil (:exhausted b))
                (binding [*out* *err*]
                  (println (str "bench/budget-exhausted run-id=" run-id
                                " limit=" (name (:exhausted b))
                                " stages=" (count (:stages @checkpoint)) "/" total))))
              (do
                (acquire-rate-gate! rate-gate min-delay-ms)
                (when-not @stop-flag
                  (let [stage-start (System/currentTimeMillis)
                        result      (run-stage stage-key question rubric-map db raw-ctx
                                               (:stages @checkpoint) invoke-llm judge-llm)
                        dur-ms      (- (System/currentTimeMillis) stage-start)]
                    (swap! session-cost + (get-in result [:usage :cost-usd] 0.0))
                    (let [new-cp    (swap! checkpoint assoc-in [:stages stage-key] result)
                          completed (count (:stages new-cp))]
                      (checkpoint-write-latest! cp-path checkpoint)
                      (binding [*out* *err*]
                        (println (str "bench/stage-complete run-id=" run-id
                                      " q=" (name qid)
                                      " condition=" (name condition)
                                      " stage=" (name stage-type)
                                      " duration=" dur-ms "ms"
                                      (when-let [su (:usage result)]
                                        (str " tokens=" (:input-tokens su)
                                             "/" (:output-tokens su)))
                                      " [" completed "/" total "]"))))))))))))
    (catch Exception e
      (when (compare-and-set! error-atom nil e)
        (compare-and-set! stop-flag nil :error)))))

;; --- Runner ---

(defn- run-pairs!
  "Process a seq of [qid condition question] pairs via pipeline-blocking.
   Shared state (checkpoint, stop-flag, etc.) is passed in `shared`."
  [pairs shared concurrency]
  (let [in-ch  (async/to-chan! (vec pairs))
        out-ch (async/chan (max 1 (count pairs)))
        skip-judge? (:skip-judge (:mode shared))]
    (async/pipeline-blocking
     concurrency out-ch
     (map (fn [[qid condition question]]
            (let [deterministic? (= :deterministic (:scoring question))
                  stage-types (cond
                                (and skip-judge? (not deterministic?)) [:answer]
                                :else [:answer :judge])]
              (run-pair! (assoc shared :qid qid :condition condition
                                :question question :stage-types stage-types)))
            :done))
     in-ch)
    (loop [] (when (async/<!! out-ch) (recur)))))

(defn run-benchmark!
  "Run the full benchmark with per-stage checkpointing, resume, and budget controls.
   Returns {:results [...] :aggregate {...} :total-usage {...} :run-id str :checkpoint-path str :stop-reason kw-or-nil}.
   `invoke-llm` is (prompt -> {:text :usage}) for answer stages.
   `:judge-llm` — optional separate LLM for judge stages (defaults to invoke-llm).
   `:model-config` — map of {:model :judge-model :provider} stored in checkpoint metadata.
   `:resume-checkpoint` — loaded checkpoint map to resume from.
   `:budget` — {:max-questions n :stop-after-ms ms :max-cost-usd d}.
   `:concurrency` — number of parallel pair workers (default 4, range 1-20).
   `:min-delay-ms` — minimum delay between LLM requests in ms (default 0).
   `:mode` — {:skip-raw bool :skip-judge bool} to control which stages run.
   `:canary` — when true, run canary questions first, then remaining."
  [db repo-path invoke-llm & {:keys [checkpoint-dir resume-checkpoint budget
                                     judge-llm model-config concurrency min-delay-ms
                                     mode canary]
                              :or   {checkpoint-dir "data/benchmarks/runs"
                                     budget         {}
                                     concurrency    4
                                     min-delay-ms   0}}]
  (let [questions       (load-questions)
        rubric-map      (load-rubric)
        judge-llm       (or judge-llm invoke-llm)
        model-config    (or model-config {:provider "claude"})
        mode            (or mode {})
        concurrency     (max 1 (min 20 concurrency))
        min-delay-ms    (max 0 min-delay-ms)
        r-hash          (question-set-hash (:judge-template rubric-map))
        ap-hash         (question-set-hash (answer-prompt "{{q}}" "{{ctx}}"))
        resuming?       (some? resume-checkpoint)
        run-id          (if resuming? (:run-id resume-checkpoint) (generate-run-id))
        all-stages      (all-stage-keys questions mode)
        total           (count all-stages)
        cp-path         (str (io/file checkpoint-dir (str run-id ".edn")))
        start-ms        (System/currentTimeMillis)
        session-cost    (atom 0.0)
        stop-flag       (atom nil)
        error-atom      (atom nil)
        rate-gate       (atom 0)
        initial-stages  (if resuming? (:stages resume-checkpoint) {})
        has-remaining?  (some #(not (contains? initial-stages %)) all-stages)
        skip-raw?       (:skip-raw mode)
        raw-ctx         (when (and has-remaining? (not skip-raw?)) (raw-context repo-path))
        checkpoint      (atom (if resuming?
                                (assoc resume-checkpoint
                                       :aggregate nil
                                       :metadata (assoc (:metadata resume-checkpoint)
                                                        :budget budget))
                                {:run-id    run-id
                                 :metadata  {:repo-path          (str repo-path)
                                             :commit-sha         (repo-head-sha repo-path)
                                             :question-set-hash  (question-set-hash questions)
                                             :model-config       model-config
                                             :mode               mode
                                             :rubric-hash        r-hash
                                             :answer-prompt-hash ap-hash
                                             :started-at         (java.util.Date.)
                                             :budget             budget}
                                 :stages    {}
                                 :aggregate nil}))
        ;; Build pairs: each pair is [qid condition question stage-types]
        conditions      (if skip-raw? [:query] [:query :raw])
        pairs           (for [q questions, cond conditions]
                          [(:id q) cond q])]
    (binding [*out* *err*]
      (println (str "bench/run-start run-id=" run-id
                    " questions=" (count questions)
                    " stages=" total
                    (when resuming?
                      (str " resume-from=" (count initial-stages) "/" total))
                    (when (> concurrency 1)
                      (str " concurrency=" concurrency))
                    (when (pos? min-delay-ms)
                      (str " min-delay=" min-delay-ms "ms"))
                    (when (:max-questions budget)
                      (str " max-questions=" (:max-questions budget)))
                    (when (:stop-after-ms budget)
                      (str " stop-after=" (:stop-after-ms budget) "ms")))))
    ;; Process pairs
    (let [stages-per-q       (if (seq mode)
                               (quot total (count questions))
                               4)
          shared {:rubric-map rubric-map :db db :raw-ctx raw-ctx
                  :checkpoint checkpoint :cp-path cp-path
                  :invoke-llm invoke-llm :judge-llm judge-llm
                  :session-cost session-cost :budget budget :start-ms start-ms
                  :stop-flag stop-flag :error-atom error-atom
                  :rate-gate rate-gate :min-delay-ms min-delay-ms
                  :run-id run-id :total total :mode mode
                  :stages-per-question stages-per-q}]
      (if canary
        ;; Two-phase: canary first, then remaining
        (let [canary-qs    (filterv #(canary-question-ids (:id %)) questions)
              remaining-qs (filterv #(not (canary-question-ids (:id %))) questions)
              canary-pairs (for [q canary-qs, cond conditions] [(:id q) cond q])
              rest-pairs   (for [q remaining-qs, cond conditions] [(:id q) cond q])]
          ;; Canary phase
          (binding [*out* *err*]
            (println (str "bench/canary-start run-id=" run-id
                          " questions=" (count canary-qs))))
          (run-pairs! canary-pairs shared concurrency)
          (when-not @stop-flag
            (let [canary-results (vec (stages->results canary-qs (:stages @checkpoint) mode))
                  eval-result    (canary-evaluate canary-results)]
              (binding [*out* *err*]
                (if (= :pass (:status eval-result))
                  (println (str "bench/canary-pass run-id=" run-id
                                " details=" (pr-str (:details eval-result))))
                  (println (str "bench/canary-warn run-id=" run-id
                                " details=" (pr-str (:details eval-result))))))))
          ;; Full phase (remaining questions)
          (when-not @stop-flag
            (run-pairs! rest-pairs shared concurrency)))
        ;; Single phase
        (run-pairs! pairs shared concurrency)))
    ;; Re-throw stored error after pipeline drains
    (when-let [e @error-atom]
      (throw e))
    (let [stop-reason @stop-flag
          results     (vec (stages->results questions (:stages @checkpoint) mode))
          agg         (aggregate-scores results mode)
          total-usage (aggregate-usage (:stages @checkpoint))
          total-ms    (- (System/currentTimeMillis) start-ms)]
      (swap! checkpoint assoc :aggregate agg :total-usage total-usage)
      (checkpoint-write cp-path @checkpoint)
      (binding [*out* *err*]
        (println (str "bench/run-complete run-id=" run-id
                      " questions=" (:question-count agg)
                      " query-mean=" (format "%.2f" (double (:query-mean agg)))
                      (when (:raw-mean agg)
                        (str " raw-mean=" (format "%.2f" (double (:raw-mean agg)))))
                      " tokens=" (:input-tokens total-usage) "/" (:output-tokens total-usage)
                      " cost=$" (format "%.4f" (double (:cost-usd total-usage 0.0)))
                      " duration=" total-ms "ms"
                      (when-not (:canonical agg) " mode=non-canonical")
                      (when stop-reason (str " stopped-by=" (name stop-reason)))))
        (when stop-reason
          (println (str "Resume with: clj -M:run benchmark " repo-path " --resume"))))
      {:results         results
       :aggregate       agg
       :total-usage     total-usage
       :run-id          run-id
       :checkpoint-path cp-path
       :stop-reason     stop-reason})))
