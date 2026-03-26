(ns noumenon.benchmark
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
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
    (->> (str/split-lines out)
         (remove str/blank?)
         (filter (fn [path]
                   (some #(str/ends-with? path %)
                         [".clj" ".cljs" ".cljc" ".java"])))
         (map (fn [path]
                (let [{:keys [out]} (shell/sh "git" "-C" (str repo-path)
                                              "show" (str "HEAD:" path))]
                  (str "--- " path " ---\n" out "\n"))))
         (str/join "\n"))))

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
   Each result has :query-score and :raw-score as keywords."
  [results]
  (let [q-scores (mapv #(score-value (:query-score %)) results)
        r-scores (mapv #(score-value (:raw-score %)) results)
        mean     (fn [xs] (if (seq xs) (/ (reduce + xs) (count xs)) 0.0))
        by-cat   (group-by :category results)]
    {:question-count (count results)
     :query-mean     (mean q-scores)
     :raw-mean       (mean r-scores)
     :per-category   (into {}
                           (map (fn [[cat rs]]
                                  [cat {:query-mean (mean (mapv #(score-value (:query-score %)) rs))
                                        :raw-mean   (mean (mapv #(score-value (:raw-score %)) rs))
                                        :count      (count rs)}]))
                           by-cat)}))

;; --- Usage tracking ---

(def zero-usage
  {:input-tokens 0 :output-tokens 0 :cost-usd 0.0 :duration-ms 0})

(defn aggregate-usage
  "Sum usage maps from all completed stages. Returns a usage map."
  [stages]
  (reduce (fn [acc stage-val]
            (if-let [u (:usage stage-val)]
              {:input-tokens  (+ (:input-tokens acc 0) (:input-tokens u 0))
               :output-tokens (+ (:output-tokens acc 0) (:output-tokens u 0))
               :cost-usd      (+ (:cost-usd acc 0.0) (:cost-usd u 0.0))
               :duration-ms   (+ (:duration-ms acc 0) (:duration-ms u 0))}
              acc))
          zero-usage
          (vals stages)))

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
  "Atomically write checkpoint EDN to path (write temp + atomic rename)."
  [path checkpoint]
  (let [f   (io/file path)
        tmp (io/file (str path ".tmp"))]
    (.mkdirs (.getParentFile f))
    (spit tmp (pr-str checkpoint))
    (Files/move (.toPath tmp) (.toPath f)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    path))

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
  (let [required   [:repo-path :commit-sha :question-set-hash :model-config]
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
   `stages-completed` is count of completed stages (4 per question).
   `session-cost-usd` is accumulated cost for this session only (excludes prior sessions on resume).
   `budget` is {:max-questions n :stop-after-ms ms :max-cost-usd d}."
  [stages-completed session-cost-usd budget started-at-ms]
  (let [{:keys [max-questions max-cost-usd stop-after-ms]} budget]
    (cond
      (and max-questions (>= stages-completed (* 4 max-questions)))
      {:exhausted :max-questions}

      (and max-cost-usd (> session-cost-usd max-cost-usd))
      {:exhausted :max-cost}

      (and stop-after-ms (>= (- (System/currentTimeMillis) started-at-ms) stop-after-ms))
      {:exhausted :stop-after}

      :else :ok)))

;; --- Stage execution ---

(defn stage-keys
  "Return the 4 ordered stage keys for a question: [id condition stage-type]."
  [question]
  (let [qid (:id question)]
    [[qid :query :answer]
     [qid :query :judge]
     [qid :raw :answer]
     [qid :raw :judge]]))

(defn all-stage-keys
  "Return all stage keys for all questions in execution order."
  [questions]
  (vec (mapcat stage-keys questions)))

(defn run-stage
  "Execute a single benchmark stage. Returns {:status :ok :result ... :usage ... :completed-at ...}.
   `invoke-llm` is (prompt -> {:text string :usage map}).
   `stages` is the map of already-completed stages (judge stages read their answer from it).
   `raw-ctx` is the precomputed raw-context string."
  [stage-key question rubric-map db raw-ctx stages invoke-llm judge-llm]
  (let [[qid condition stage-type] stage-key
        q-text  (:question question)
        llm-fn  (if (= :judge stage-type) judge-llm invoke-llm)
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
     :completed-at (java.util.Date.)}))

(defn stages->results
  "Convert stages map to result seq. Only includes fully-completed questions (all 4 stages)."
  [questions stages]
  (for [q questions
        :let [qid  (:id q)
              keys (stage-keys q)]
        :when (every? #(contains? stages %) keys)
        :let [q-judge (get-in stages [[qid :query :judge] :result])
              r-judge (get-in stages [[qid :raw :judge] :result])]]
    {:id              (:id q)
     :category        (:category q)
     :query-name      (:query-name q)
     :query-answer    (get-in stages [[qid :query :answer] :result])
     :query-score     (or (:score q-judge) :wrong)
     :query-reasoning (:reasoning q-judge)
     :raw-answer      (get-in stages [[qid :raw :answer] :result])
     :raw-score       (or (:score r-judge) :wrong)
     :raw-reasoning   (:reasoning r-judge)}))

;; --- Runner ---

(defn run-benchmark!
  "Run the full benchmark with per-stage checkpointing, resume, and budget controls.
   Returns {:results [...] :aggregate {...} :total-usage {...} :run-id str :checkpoint-path str :stop-reason kw-or-nil}.
   `invoke-llm` is (prompt -> {:text :usage}) for answer stages.
   `:judge-llm` — optional separate LLM for judge stages (defaults to invoke-llm).
   `:model-config` — map of {:model :judge-model :provider} stored in checkpoint metadata.
   `:resume-checkpoint` — loaded checkpoint map to resume from.
   `:budget` — {:max-questions n :stop-after-ms ms :max-cost-usd d}."
  [db repo-path invoke-llm & {:keys [checkpoint-dir resume-checkpoint budget
                                     judge-llm model-config]
                              :or   {checkpoint-dir "data/benchmarks/runs"
                                     budget         {}}}]
  (let [questions       (load-questions)
        rubric-map      (load-rubric)
        judge-llm       (or judge-llm invoke-llm)
        model-config    (or model-config {:provider "claude"})
        r-hash          (question-set-hash (:judge-template rubric-map))
        ap-hash         (question-set-hash (answer-prompt "{{q}}" "{{ctx}}"))
        resuming?       (some? resume-checkpoint)
        run-id          (if resuming? (:run-id resume-checkpoint) (generate-run-id))
        all-stages      (all-stage-keys questions)
        total           (count all-stages)
        questions-by-id (into {} (map (juxt :id identity)) questions)
        cp-path         (str (io/file checkpoint-dir (str run-id ".edn")))
        start-ms        (System/currentTimeMillis)
        session-cost    (atom 0.0)
        initial-stages  (if resuming? (:stages resume-checkpoint) {})
        has-remaining?  (some #(not (contains? initial-stages %)) all-stages)
        raw-ctx         (when has-remaining? (raw-context repo-path))
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
                                             :rubric-hash        r-hash
                                             :answer-prompt-hash ap-hash
                                             :started-at         (java.util.Date.)
                                             :budget             budget}
                                 :stages    {}
                                 :aggregate nil}))]
    (binding [*out* *err*]
      (println (str "bench/run-start run-id=" run-id
                    " questions=" (count questions)
                    " stages=" total
                    (when resuming?
                      (str " resume-from=" (count initial-stages) "/" total))
                    (when (:max-questions budget)
                      (str " max-questions=" (:max-questions budget)))
                    (when (:stop-after-ms budget)
                      (str " stop-after=" (:stop-after-ms budget) "ms")))))
    (let [stop-reason
          (loop [remaining all-stages]
            (if (empty? remaining)
              nil
              (let [stage-key (first remaining)
                    [qid condition stage-type] stage-key]
                (if (contains? (:stages @checkpoint) stage-key)
                  (do (binding [*out* *err*]
                        (println (str "bench/stage-skip run-id=" run-id
                                      " q=" (name qid)
                                      " condition=" (name condition)
                                      " stage=" (name stage-type)
                                      " reason=already-complete")))
                      (recur (rest remaining)))
                  (let [b (budget-check (count (:stages @checkpoint)) @session-cost budget start-ms)]
                    (if (not= :ok b)
                      (do (binding [*out* *err*]
                            (println (str "bench/budget-exhausted run-id=" run-id
                                          " limit=" (name (:exhausted b))
                                          " stages=" (count (:stages @checkpoint)) "/" total)))
                          (:exhausted b))
                      (let [question    (questions-by-id qid)
                            stage-start (System/currentTimeMillis)
                            result      (run-stage stage-key question rubric-map db raw-ctx
                                                   (:stages @checkpoint) invoke-llm judge-llm)
                            dur-ms      (- (System/currentTimeMillis) stage-start)
                            _           (swap! session-cost + (get-in result [:usage :cost-usd] 0.0))
                            new-cp      (swap! checkpoint assoc-in [:stages stage-key] result)
                            completed   (count (:stages new-cp))]
                        (checkpoint-write cp-path new-cp)
                        (let [su (:usage result)]
                          (binding [*out* *err*]
                            (println (str "bench/stage-complete run-id=" run-id
                                          " q=" (name qid)
                                          " condition=" (name condition)
                                          " stage=" (name stage-type)
                                          " duration=" dur-ms "ms"
                                          (when su
                                            (str " tokens=" (:input-tokens su)
                                                 "/" (:output-tokens su)))
                                          " [" completed "/" total "]"))))
                        (recur (rest remaining)))))))))
          results     (vec (stages->results questions (:stages @checkpoint)))
          agg         (aggregate-scores results)
          total-usage (aggregate-usage (:stages @checkpoint))
          total-ms    (- (System/currentTimeMillis) start-ms)]
      (swap! checkpoint assoc :aggregate agg :total-usage total-usage)
      (checkpoint-write cp-path @checkpoint)
      (binding [*out* *err*]
        (println (str "bench/run-complete run-id=" run-id
                      " questions=" (:question-count agg)
                      " query-mean=" (format "%.2f" (double (:query-mean agg)))
                      " raw-mean=" (format "%.2f" (double (:raw-mean agg)))
                      " tokens=" (:input-tokens total-usage) "/" (:output-tokens total-usage)
                      " cost=$" (format "%.4f" (double (:cost-usd total-usage 0.0)))
                      " duration=" total-ms "ms"
                      (when stop-reason (str " stopped-by=" (name stop-reason)))))
        (when stop-reason
          (println (str "Resume with: clj -M:run benchmark " repo-path " --resume"))))
      {:results         results
       :aggregate       agg
       :total-usage     total-usage
       :run-id          run-id
       :checkpoint-path cp-path
       :stop-reason     stop-reason})))

(defn save-results!
  "Save benchmark results to data/benchmarks/ as EDN."
  [results]
  (let [dir  (io/file "data" "benchmarks")
        ts   (System/currentTimeMillis)
        file (io/file dir (str "benchmark-" ts ".edn"))]
    (.mkdirs dir)
    (spit file (pr-str results))
    (binding [*out* *err*]
      (println (str "Results saved to " (.getPath file))))
    (.getPath file)))
