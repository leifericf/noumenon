(ns noumenon.longbench
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.benchmark :as bench])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpResponse$BodyHandlers]
           [java.nio.file Files StandardCopyOption]
           [java.time Duration]))

;; --- Constants ---

(def ^:private dataset-url
  "https://huggingface.co/datasets/THUDM/LongBench-v2/resolve/main/data.json")

(def ^:private data-dir "data/longbench")
(def ^:private data-file "data/longbench/data.json")
(def ^:private code-repo-domain "Code Repository Understanding")
(def ^:private max-context-tokens 200000)

;; --- Download ---

(defn download-dataset!
  "Download LongBench v2 dataset from HuggingFace. Skips if already cached.
   Returns {:path str :total-items n :code-repo-items n}."
  []
  (let [f (io/file data-file)]
    (if (.exists f)
      (do (binding [*out* *err*]
            (println (str "longbench/dataset-exists path=" (.getAbsolutePath f))))
          {:path (.getPath f) :already-cached true})
      (do
        (binding [*out* *err*]
          (println "longbench/download-start"))
        (.mkdirs (io/file data-dir))
        (let [client   (-> (HttpClient/newBuilder)
                           (.followRedirects HttpClient$Redirect/ALWAYS)
                           (.connectTimeout (Duration/ofSeconds 30))
                           .build)
              request  (-> (HttpRequest/newBuilder)
                           (.uri (URI/create dataset-url))
                           (.timeout (Duration/ofMinutes 30))
                           .build)
              response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
              status   (.statusCode response)
              tmp      (io/file (str data-file ".tmp"))]
          (when (not= 200 status)
            (throw (ex-info (str "Download failed: HTTP " status
                                 ". Manual download: " dataset-url)
                            {:status status :url dataset-url})))
          (with-open [in (.body response)
                      out (io/output-stream tmp)]
            (io/copy in out :buffer-size 65536))
          (Files/move (.toPath tmp) (.toPath f)
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))
          (let [items    (with-open [rdr (io/reader f)]
                           (json/read rdr :key-fn keyword))
                total    (count items)
                code-cnt (count (filter #(= code-repo-domain (:domain %)) items))]
            (binding [*out* *err*]
              (println (str "longbench/download-complete items=" total
                            " code-repo=" code-cnt)))
            {:path (.getPath f) :total-items total :code-repo-items code-cnt}))))))

;; --- Loading ---

(defn filter-code-repo-questions
  "Filter dataset items to code repository understanding questions."
  [items]
  (filterv #(= code-repo-domain (:domain %)) items))

(defn load-code-repo-questions
  "Load code repo questions from cached dataset. Returns vec of maps."
  []
  (let [f (io/file data-file)]
    (when-not (.exists f)
      (throw (ex-info "Dataset not found. Run: clj -M:run longbench download"
                      {:path data-file})))
    (->> (with-open [rdr (io/reader f)]
           (json/read rdr :key-fn keyword))
         filter-code-repo-questions)))

;; --- Prompt building ---

(def ^:private prompt-template
  (delay (slurp (io/resource "longbench/prompt-zero-shot.txt"))))

(defn build-prompt
  "Build the zero-shot prompt by substituting $DOC$, $Q$, $C_A$-$C_D$.
   Uses the exact LongBench v2 template."
  [{:keys [context question choice_A choice_B choice_C choice_D]}]
  (-> @prompt-template
      (str/replace "$DOC$" (str/trim (or context "")))
      (str/replace "$Q$" (str/trim (or question "")))
      (str/replace "$C_A$" (str/trim (or choice_A "")))
      (str/replace "$C_B$" (str/trim (or choice_B "")))
      (str/replace "$C_C$" (str/trim (or choice_C "")))
      (str/replace "$C_D$" (str/trim (or choice_D "")))))

;; --- Context truncation ---

(defn truncate-middle
  "Middle-truncate a string when estimated tokens exceed max-context-tokens.
   Token estimation: chars/4. Keeps first half + last half of characters.
   Returns the (possibly truncated) string."
  ([s] (truncate-middle s max-context-tokens))
  ([s max-tokens]
   (let [max-chars (* max-tokens 4)
         n         (count s)]
     (if (<= n max-chars)
       s
       (let [half (/ max-chars 2)]
         (str (subs s 0 half) (subs s (- n half))))))))

;; --- Answer extraction ---

(defn extract-answer
  "Extract answer letter from LLM response using LongBench v2's two-tier regex.
   Strips asterisks first (handles markdown bold). Returns A/B/C/D or nil."
  [response]
  (when response
    (let [clean (str/replace response "*" "")]
      (or (when-let [m (re-find #"The correct answer is \(([A-D])\)" clean)]
            (second m))
          (when-let [m (re-find #"The correct answer is ([A-D])" clean)]
            (second m))))))

;; --- Scoring ---

(defn score-question
  "Score a single question: exact match of prediction vs ground truth.
   nil prediction scores as incorrect (0.0)."
  [prediction ground-truth]
  (if (and prediction (= prediction ground-truth))
    1.0
    0.0))

(defn aggregate-results
  "Compute accuracy overall, by difficulty (easy/hard), and by length (short/medium/long).
   Input: seq of {:prediction str :answer str :difficulty str :length str}.
   Returns {:overall pct :easy pct :hard pct :short pct :medium pct :long pct}."
  [results]
  (let [accuracy (fn [rs]
                   (if (seq rs)
                     (* 100.0 (/ (count (filter #(= 1.0 (:score %)) rs))
                                 (count rs)))
                     nil))
        scored   (mapv #(assoc % :score (score-question (:prediction %) (:answer %)))
                       results)
        by-diff  (group-by :difficulty scored)
        by-len   (group-by :length scored)]
    {:overall  (accuracy scored)
     :easy     (accuracy (get by-diff "easy"))
     :hard     (accuracy (get by-diff "hard"))
     :short    (accuracy (get by-len "short"))
     :medium   (accuracy (get by-len "medium"))
     :long     (accuracy (get by-len "long"))
     :total    (count scored)}))

;; --- Budget check (1 question = 1 unit, unlike benchmark's 4-stage model) ---

(defn- budget-check
  "Check if budget is exhausted. Returns :ok or {:exhausted reason}."
  [questions-completed session-cost-usd budget started-at-ms]
  (let [{:keys [max-questions max-cost-usd stop-after-ms]} budget]
    (cond
      (and max-questions (>= questions-completed max-questions))
      {:exhausted :max-questions}

      (and max-cost-usd (> session-cost-usd max-cost-usd))
      {:exhausted :max-cost}

      (and stop-after-ms (>= (- (System/currentTimeMillis) started-at-ms) stop-after-ms))
      {:exhausted :stop-after}

      :else :ok)))

;; --- Per-question result JSONL ---

(defn- result-jsonl-path
  "Path for per-question JSONL results."
  [run-id]
  (str "data/longbench/results/" run-id ".jsonl"))

(defn- write-result-line!
  "Append one result line to JSONL file."
  [path question prediction response]
  (let [f   (io/file path)
        rec {:_id        (:_id question)
             :domain     (:domain question)
             :difficulty (:difficulty question)
             :length     (:length question)
             :question   (:question question)
             :answer     (:answer question)
             :response   response
             :pred       prediction
             :judge      (= prediction (:answer question))
             :context    (let [ctx (:context question)]
                           (if (> (count ctx) 1000)
                             (subs ctx 0 1000)
                             ctx))}]
    (.mkdirs (.getParentFile f))
    (spit path (str (json/write-str rec) "\n") :append true)))

;; --- Runner ---

(defn run-longbench!
  "Run LongBench v2 benchmark on code repo questions.
   `invoke-llm` is (fn [prompt] -> {:text :usage :model}).
   Returns {:results [...] :aggregate {...} :total-usage {...} :run-id str :stop-reason kw-or-nil}."
  [invoke-llm & {:keys [checkpoint-dir budget concurrency min-delay-ms]
                 :or   {checkpoint-dir "data/longbench/runs"
                        budget         {}
                        concurrency    4
                        min-delay-ms   0}}]
  (let [questions       (load-code-repo-questions)
        total           (count questions)
        concurrency     (max 1 (min 20 concurrency))
        min-delay-ms    (max 0 min-delay-ms)
        run-id          (bench/generate-run-id)
        cp-path         (str (io/file checkpoint-dir (str run-id ".edn")))
        jsonl-path      (result-jsonl-path run-id)
        start-ms        (System/currentTimeMillis)
        session-cost    (atom 0.0)
        stop-flag       (atom nil)
        error-atom      (atom nil)
        rate-gate       (atom 0)
        checkpoint      (atom {:run-id   run-id
                               :metadata {:started-at (java.util.Date.)
                                          :budget     budget}
                               :results  {}})
        remaining       (vec questions)]
    (binding [*out* *err*]
      (println (str "longbench/run-start run-id=" run-id
                    " questions=" total
                    (when (> concurrency 1)
                      (str " concurrency=" concurrency))
                    (when (pos? min-delay-ms)
                      (str " min-delay=" min-delay-ms "ms"))
                    (when (:max-questions budget)
                      (str " max-questions=" (:max-questions budget))))))
    ;; Process questions via pipeline-blocking
    (let [in-ch  (async/to-chan! remaining)
          out-ch (async/chan (count remaining))]
      (async/pipeline-blocking
       concurrency out-ch
       (map (fn [question]
              (try
                (when-not @stop-flag
                  (let [completed (count (:results @checkpoint))
                        b        (budget-check completed @session-cost budget start-ms)]
                    (if (not= :ok b)
                      (when (compare-and-set! stop-flag nil (:exhausted b))
                        (binding [*out* *err*]
                          (println (str "longbench/budget-exhausted limit="
                                        (name (:exhausted b))))))
                      (do
                        (bench/acquire-rate-gate! rate-gate min-delay-ms)
                        (when-not @stop-flag
                          (let [ctx      (truncate-middle (:context question))
                                _        (when (not= (count ctx) (count (:context question)))
                                           (binding [*out* *err*]
                                             (println (str "longbench/context-truncated"
                                                           " q=" (:_id question)
                                                           " original=" (count (:context question))
                                                           " truncated=" (count ctx)))))
                                prompt   (build-prompt (assoc question :context ctx))
                                response (invoke-llm [{:role "user" :content prompt}])
                                pred     (extract-answer (:text response))
                                correct  (= pred (:answer question))]
                            (swap! session-cost + (get-in response [:usage :cost-usd] 0.0))
                            (swap! checkpoint assoc-in [:results (:_id question)]
                                   {:prediction pred
                                    :correct    correct
                                    :usage      (:usage response)})
                            (bench/checkpoint-write-latest! cp-path checkpoint)
                            (write-result-line! jsonl-path question pred (:text response))
                            (binding [*out* *err*]
                              (println (str "longbench/question-complete"
                                            " q=" (:_id question)
                                            " pred=" (or pred "nil")
                                            " correct=" correct
                                            " [" (count (:results @checkpoint))
                                            "/" total "]")))))))))
                (catch Exception e
                  (when (compare-and-set! error-atom nil e)
                    (compare-and-set! stop-flag nil :error))))
              :done))
       in-ch)
      (loop [] (when (async/<!! out-ch) (recur))))
    ;; Re-throw stored error
    (when-let [e @error-atom]
      (throw e))
    ;; Aggregate
    (let [stop-reason @stop-flag
          result-data (for [q questions
                            :let [r (get-in @checkpoint [:results (:_id q)])]
                            :when r]
                        {:_id        (:_id q)
                         :prediction (:prediction r)
                         :answer     (:answer q)
                         :difficulty (:difficulty q)
                         :length     (:length q)})
          agg         (aggregate-results result-data)
          total-usage (reduce (fn [acc [_ r]]
                                (if-let [u (:usage r)]
                                  {:input-tokens  (+ (:input-tokens acc 0) (:input-tokens u 0))
                                   :output-tokens (+ (:output-tokens acc 0) (:output-tokens u 0))
                                   :cost-usd      (+ (:cost-usd acc 0.0) (:cost-usd u 0.0))
                                   :duration-ms   (+ (:duration-ms acc 0) (:duration-ms u 0))}
                                  acc))
                              bench/zero-usage
                              (:results @checkpoint))
          total-ms    (- (System/currentTimeMillis) start-ms)]
      (swap! checkpoint assoc :aggregate agg :total-usage total-usage)
      (bench/checkpoint-write cp-path @checkpoint)
      (binding [*out* *err*]
        (println (str "longbench/run-complete run-id=" run-id
                      " accuracy=" (when (:overall agg) (format "%.1f" (:overall agg))) "%"
                      " questions=" (:total agg)
                      " tokens=" (:input-tokens total-usage) "/" (:output-tokens total-usage)
                      " cost=$" (format "%.4f" (double (:cost-usd total-usage 0.0)))
                      " duration=" total-ms "ms"
                      (when stop-reason (str " stopped-by=" (name stop-reason)))))
        (when stop-reason
          (println "Resume with: clj -M:run longbench run --resume")))
      {:results         (vec result-data)
       :aggregate       agg
       :total-usage     total-usage
       :run-id          run-id
       :checkpoint-path cp-path
       :stop-reason     stop-reason})))

;; --- Resume ---

(defn run-longbench-resume!
  "Resume a LongBench v2 benchmark from checkpoint.
   Skips already-completed questions, runs remaining."
  [invoke-llm checkpoint & {:keys [checkpoint-dir budget concurrency min-delay-ms]
                            :or   {checkpoint-dir "data/longbench/runs"
                                   budget         {}
                                   concurrency    4
                                   min-delay-ms   0}}]
  (let [questions       (load-code-repo-questions)
        total           (count questions)
        concurrency     (max 1 (min 20 concurrency))
        min-delay-ms    (max 0 min-delay-ms)
        run-id          (:run-id checkpoint)
        cp-path         (str (io/file checkpoint-dir (str run-id ".edn")))
        jsonl-path      (result-jsonl-path run-id)
        completed-ids   (set (keys (:results checkpoint)))
        remaining       (filterv #(not (completed-ids (:_id %))) questions)
        start-ms        (System/currentTimeMillis)
        session-cost    (atom 0.0)
        stop-flag       (atom nil)
        error-atom      (atom nil)
        rate-gate       (atom 0)
        checkpoint-atom (atom checkpoint)]
    (binding [*out* *err*]
      (println (str "longbench/run-start run-id=" run-id
                    " questions=" total
                    " resume-from=" (count completed-ids) "/" total
                    " remaining=" (count remaining)
                    (when (> concurrency 1)
                      (str " concurrency=" concurrency)))))
    (let [in-ch  (async/to-chan! remaining)
          out-ch (async/chan (count remaining))]
      (async/pipeline-blocking
       concurrency out-ch
       (map (fn [question]
              (try
                (when-not @stop-flag
                  (let [completed (count (:results @checkpoint-atom))
                        b        (budget-check completed @session-cost budget start-ms)]
                    (if (not= :ok b)
                      (when (compare-and-set! stop-flag nil (:exhausted b))
                        (binding [*out* *err*]
                          (println (str "longbench/budget-exhausted limit="
                                        (name (:exhausted b))))))
                      (do
                        (bench/acquire-rate-gate! rate-gate min-delay-ms)
                        (when-not @stop-flag
                          (let [ctx      (truncate-middle (:context question))
                                prompt   (build-prompt (assoc question :context ctx))
                                response (invoke-llm [{:role "user" :content prompt}])
                                pred     (extract-answer (:text response))
                                correct  (= pred (:answer question))]
                            (swap! session-cost + (get-in response [:usage :cost-usd] 0.0))
                            (swap! checkpoint-atom assoc-in [:results (:_id question)]
                                   {:prediction pred
                                    :correct    correct
                                    :usage      (:usage response)})
                            (bench/checkpoint-write-latest! cp-path checkpoint-atom)
                            (write-result-line! jsonl-path question pred (:text response))
                            (binding [*out* *err*]
                              (println (str "longbench/question-complete"
                                            " q=" (:_id question)
                                            " pred=" (or pred "nil")
                                            " correct=" correct
                                            " [" (count (:results @checkpoint-atom))
                                            "/" total "]")))))))))
                (catch Exception e
                  (when (compare-and-set! error-atom nil e)
                    (compare-and-set! stop-flag nil :error))))
              :done))
       in-ch)
      (loop [] (when (async/<!! out-ch) (recur))))
    (when-let [e @error-atom]
      (throw e))
    (let [stop-reason @stop-flag
          result-data (for [q questions
                            :let [r (get-in @checkpoint-atom [:results (:_id q)])]
                            :when r]
                        {:_id        (:_id q)
                         :prediction (:prediction r)
                         :answer     (:answer q)
                         :difficulty (:difficulty q)
                         :length     (:length q)})
          agg         (aggregate-results result-data)
          total-usage (reduce (fn [acc [_ r]]
                                (if-let [u (:usage r)]
                                  {:input-tokens  (+ (:input-tokens acc 0) (:input-tokens u 0))
                                   :output-tokens (+ (:output-tokens acc 0) (:output-tokens u 0))
                                   :cost-usd      (+ (:cost-usd acc 0.0) (:cost-usd u 0.0))
                                   :duration-ms   (+ (:duration-ms acc 0) (:duration-ms u 0))}
                                  acc))
                              bench/zero-usage
                              (:results @checkpoint-atom))
          total-ms    (- (System/currentTimeMillis) start-ms)]
      (swap! checkpoint-atom assoc :aggregate agg :total-usage total-usage)
      (bench/checkpoint-write cp-path @checkpoint-atom)
      (binding [*out* *err*]
        (println (str "longbench/run-complete run-id=" run-id
                      " accuracy=" (when (:overall agg) (format "%.1f" (:overall agg))) "%"
                      " questions=" (:total agg)
                      " cost=$" (format "%.4f" (double (:cost-usd total-usage 0.0)))
                      " duration=" total-ms "ms"
                      (when stop-reason (str " stopped-by=" (name stop-reason))))))
      {:results         (vec result-data)
       :aggregate       agg
       :total-usage     total-usage
       :run-id          run-id
       :checkpoint-path cp-path
       :stop-reason     stop-reason})))

;; --- Results loading ---

(defn load-results
  "Load per-question results from JSONL file. Returns vec of maps."
  [run-id]
  (let [path (result-jsonl-path run-id)
        f    (io/file path)]
    (when (.exists f)
      (->> (str/split-lines (slurp f))
           (remove str/blank?)
           (mapv #(json/read-str % :key-fn keyword))))))

(defn find-latest-run
  "Find the most recent run ID from checkpoint files."
  []
  (bench/find-checkpoint "data/longbench/runs" "latest"))
