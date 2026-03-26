(ns noumenon.longbench
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.benchmark :as bench]
            [noumenon.cli :as cli]
            [noumenon.llm :as llm]
            [noumenon.pipeline :as pipeline]
            [noumenon.util :refer [log!]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpResponse$BodyHandlers]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.time Duration]))

;; --- Constants ---

(def ^:private dataset-url
  "https://huggingface.co/datasets/THUDM/LongBench-v2/resolve/main/data.json")

(def ^:private data-dir "data/longbench")
(def ^:private data-file "data/longbench/data.json")
(def ^:private code-repo-domain "Code Repository Understanding")
(def ^:private max-context-tokens 200000)

;; SHA-256 of the expected dataset file. Set after first verified download.
;; To update: sha256sum data/longbench/data.json and paste the hex digest here.
(def ^:private expected-sha256 nil)

;; --- Download ---

(defn- sha256-hex
  "Compute SHA-256 hex digest of a file."
  [^java.io.File f]
  (let [md  (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [in (io/input-stream f)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (format "%064x" (BigInteger. 1 (.digest md)))))

(defn- verify-integrity!
  "Verify SHA-256 of downloaded file. Throws on mismatch. Skips when no
   expected hash is configured (logs the actual hash for future pinning)."
  [^java.io.File f]
  (let [actual (sha256-hex f)]
    (if (nil? expected-sha256)
      (log! (str "longbench/integrity-skip"
                 " actual-sha256=" actual
                 " — pin this hash in expected-sha256 after verification"))
      (when (not= actual expected-sha256)
        (throw (ex-info "Dataset integrity check failed: SHA-256 mismatch"
                        {:expected expected-sha256 :actual actual}))))))

(defn download-dataset!
  "Download LongBench v2 dataset from HuggingFace. Skips if already cached.
   Returns {:path str :total-items n :code-repo-items n}."
  []
  (let [f (io/file data-file)]
    (if (.exists f)
      (do (log! (str "longbench/dataset-exists path=" (.getAbsolutePath f)))
          {:path (.getPath f) :already-cached true})
      (do
        (log! "longbench/download-start")
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
          (verify-integrity! f)
          (let [items    (with-open [rdr (io/reader f)]
                           (json/read rdr :key-fn keyword))
                total    (count items)
                code-cnt (count (filter #(= code-repo-domain (:domain %)) items))]
            (log! (str "longbench/download-complete items=" total
                       " code-repo=" code-cnt))
            {:path (.getPath f) :total-items total :code-repo-items code-cnt}))))))

;; --- Loading ---

(defn filter-code-repo-questions
  "Filter dataset items to code repository understanding questions."
  [items]
  (filterv (comp #{code-repo-domain} :domain) items))

(defn load-code-repo-questions
  "Load code repo questions from cached dataset. Returns vec of maps."
  []
  (let [f (io/file data-file)]
    (when-not (.exists f)
      (throw (ex-info "Dataset not found. Run: noumenon longbench download"
                      {:path data-file})))
    (->> (with-open [rdr (io/reader f)]
           (json/read rdr :key-fn keyword))
         filter-code-repo-questions)))

;; --- Prompt building ---

(def ^:private prompt-template
  (delay (slurp (io/resource "longbench/prompt-zero-shot.txt"))))

(defn build-prompt
  "Build the zero-shot prompt by substituting $DOC$, $Q$, $C_A$-$C_D$.
   Uses a single-pass regex replacement to prevent template variable smuggling
   (e.g. a context field containing literal '$Q$' being treated as a placeholder)."
  [{:keys [context question choice_A choice_B choice_C choice_D]}]
  (let [subs-map {"$DOC$" (str/trim (or context ""))
                  "$Q$"   (str/trim (or question ""))
                  "$C_A$" (str/trim (or choice_A ""))
                  "$C_B$" (str/trim (or choice_B ""))
                  "$C_C$" (str/trim (or choice_C ""))
                  "$C_D$" (str/trim (or choice_D ""))}
        pattern  (re-pattern (str/join "|" (map #(java.util.regex.Pattern/quote %) (keys subs-map))))]
    (str/replace @prompt-template pattern subs-map)))

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

;; Budget check: reuse benchmark's with stages-per-question=1
;; (longbench counts 1 question = 1 unit, not 4 stages)

;; --- Per-question result JSONL ---

(def ^:private jsonl-lock (Object.))

(defn- result-jsonl-path
  "Path for per-question JSONL results. Validates run-id to prevent path traversal."
  [run-id]
  (bench/validate-run-id run-id)
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
    (locking jsonl-lock
      (spit path (str (json/write-str rec) "\n") :append true))))

;; --- Shared pipeline ---

(defn- run-and-record-question!
  "Invoke LLM for one question, record prediction in checkpoint and JSONL."
  [{:keys [question invoke-llm checkpoint-atom cp-path jsonl-path total session-cost]}]
  (let [raw-ctx  (:context question)
        ctx      (truncate-middle raw-ctx)
        _        (when (not= (count ctx) (count raw-ctx))
                   (log! (str "longbench/context-truncated"
                              " q=" (:_id question)
                              " original=" (count raw-ctx)
                              " truncated=" (count ctx))))
        prompt   (build-prompt (assoc question :context ctx))
        response (invoke-llm [{:role "user" :content prompt}])
        pred     (extract-answer (:text response))
        correct  (= pred (:answer question))]
    (swap! session-cost + (get-in response [:usage :cost-usd] 0.0))
    (swap! checkpoint-atom assoc-in [:results (:_id question)]
           {:prediction pred :correct correct :usage (:usage response)})
    (bench/checkpoint-write-latest! cp-path checkpoint-atom)
    (write-result-line! jsonl-path question pred (:text response))
    (log! (str "longbench/question-complete"
               " q=" (:_id question)
               " pred=" (or pred "nil")
               " correct=" correct
               " [" (count (:results @checkpoint-atom)) "/" total "]"))))

(defn- process-question!
  "Check budget, acquire rate gate, then run LLM on one question."
  [{:keys [checkpoint-atom session-cost stop-flag budget start-ms
           rate-gate min-delay-ms] :as opts}]
  (let [completed (count (:results @checkpoint-atom))
        b         (bench/budget-check completed @session-cost budget start-ms
                                      (:max-questions budget))]
    (if (not= :ok b)
      (when (compare-and-set! stop-flag nil (:exhausted b))
        (log! (str "longbench/budget-exhausted limit=" (name (:exhausted b)))))
      (do
        (bench/acquire-rate-gate! rate-gate min-delay-ms)
        (when-not @stop-flag
          (run-and-record-question! opts))))))

(defn- run-pipeline!
  "Run LLM pipeline over remaining questions. Shared by fresh and resume paths.
   Returns stop-reason keyword or nil. Mutates checkpoint-atom in place."
  [invoke-llm remaining checkpoint-atom
   {:keys [cp-path jsonl-path total budget concurrency min-delay-ms]}]
  (let [session-cost (atom 0.0)
        stop-flag    (atom nil)
        error-atom   (atom nil)
        rate-gate    (atom 0)
        start-ms     (System/currentTimeMillis)
        shared       {:invoke-llm invoke-llm :checkpoint-atom checkpoint-atom
                      :cp-path cp-path :jsonl-path jsonl-path :total total
                      :session-cost session-cost :stop-flag stop-flag
                      :budget budget :start-ms start-ms
                      :rate-gate rate-gate :min-delay-ms min-delay-ms}]
    (pipeline/run-concurrent!
     remaining
     {:concurrency concurrency
      :stop-flag stop-flag
      :error-atom error-atom
      :process-item! (fn [question]
                       (process-question! (assoc shared :question question)))})))

(defn- finalize-run
  "Aggregate results, write final checkpoint, log summary. Returns result map."
  [questions checkpoint-atom cp-path run-id start-ms stop-reason]
  (let [result-data (for [q questions
                          :let [r (get-in @checkpoint-atom [:results (:_id q)])]
                          :when r]
                      {:_id        (:_id q)
                       :prediction (:prediction r)
                       :answer     (:answer q)
                       :difficulty (:difficulty q)
                       :length     (:length q)})
        agg         (aggregate-results result-data)
        total-usage (reduce llm/sum-usage llm/zero-usage
                            (keep :usage (vals (:results @checkpoint-atom))))
        total-ms    (- (System/currentTimeMillis) start-ms)]
    (swap! checkpoint-atom assoc :aggregate agg :total-usage total-usage)
    (bench/checkpoint-write cp-path @checkpoint-atom)
    (log! (str "longbench/run-complete run-id=" run-id
               " accuracy=" (when (:overall agg) (format "%.1f" (:overall agg))) "%"
               " questions=" (:total agg)
               " tokens=" (:input-tokens total-usage) "/" (:output-tokens total-usage)
               " cost=$" (format "%.4f" (double (:cost-usd total-usage 0.0)))
               " duration=" total-ms "ms"
               (when stop-reason (str " stopped-by=" (name stop-reason)))))
    (when stop-reason
      (log! (str "Resume with: " cli/program-name " longbench run --resume")))
    {:results         (vec result-data)
     :aggregate       agg
     :total-usage     total-usage
     :run-id          run-id
     :checkpoint-path cp-path
     :stop-reason     stop-reason}))

;; --- Runner ---

(defn run-longbench!
  "Run LongBench v2 benchmark on code repo questions.
   `invoke-llm` is (fn [messages] -> {:text :usage :model}).
   Returns {:results [...] :aggregate {...} :total-usage {...} :run-id str :stop-reason kw-or-nil}."
  [invoke-llm & {:keys [checkpoint-dir budget concurrency min-delay-ms]
                 :or   {checkpoint-dir "data/longbench/runs"
                        budget         {}
                        concurrency    3
                        min-delay-ms   0}}]
  (let [questions    (load-code-repo-questions)
        total        (count questions)
        concurrency  (max 1 (min 20 concurrency))
        min-delay-ms (max 0 min-delay-ms)
        run-id       (bench/generate-run-id)
        cp-path      (str (io/file checkpoint-dir (str run-id ".edn")))
        jsonl-path   (result-jsonl-path run-id)
        start-ms     (System/currentTimeMillis)
        checkpoint   (atom {:run-id   run-id
                            :metadata {:started-at (java.util.Date.)
                                       :budget     budget}
                            :results  {}})]
    (log! (str "longbench/run-start run-id=" run-id
               " questions=" total
               (when (> concurrency 1) (str " concurrency=" concurrency))
               (when (pos? min-delay-ms) (str " min-delay=" min-delay-ms "ms"))
               (when (:max-questions budget) (str " max-questions=" (:max-questions budget)))))
    (let [stop-reason (run-pipeline! invoke-llm questions checkpoint
                                     {:cp-path cp-path :jsonl-path jsonl-path
                                      :total total :budget budget
                                      :concurrency concurrency :min-delay-ms min-delay-ms})]
      (finalize-run questions checkpoint cp-path run-id start-ms stop-reason))))

;; --- Resume ---

(defn run-longbench-resume!
  "Resume a LongBench v2 benchmark from checkpoint.
   Skips already-completed questions, runs remaining."
  [invoke-llm prior-checkpoint & {:keys [checkpoint-dir budget concurrency min-delay-ms]
                                  :or   {checkpoint-dir "data/longbench/runs"
                                         budget         {}
                                         concurrency    3
                                         min-delay-ms   0}}]
  (let [questions     (load-code-repo-questions)
        total         (count questions)
        concurrency   (max 1 (min 20 concurrency))
        min-delay-ms  (max 0 min-delay-ms)
        run-id        (:run-id prior-checkpoint)
        cp-path       (str (io/file checkpoint-dir (str run-id ".edn")))
        jsonl-path    (result-jsonl-path run-id)
        completed-ids (set (keys (:results prior-checkpoint)))
        remaining     (filterv (comp not completed-ids :_id) questions)
        start-ms      (System/currentTimeMillis)
        checkpoint    (atom prior-checkpoint)]
    (log! (str "longbench/run-start run-id=" run-id
               " questions=" total
               " resume-from=" (count completed-ids) "/" total
               " remaining=" (count remaining)
               (when (> concurrency 1) (str " concurrency=" concurrency))))
    (let [stop-reason (run-pipeline! invoke-llm remaining checkpoint
                                     {:cp-path cp-path :jsonl-path jsonl-path
                                      :total total :budget budget
                                      :concurrency concurrency :min-delay-ms min-delay-ms})]
      (finalize-run questions checkpoint cp-path run-id start-ms stop-reason))))

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
