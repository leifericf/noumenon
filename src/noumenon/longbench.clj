(ns noumenon.longbench
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.benchmark :as bench]
            [noumenon.llm :as llm]
            [noumenon.pipeline :as pipeline]
            [noumenon.util :as util :refer [log!]])
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
          (let [content-len (-> response .headers (.firstValue "content-length")
                                (.orElse nil))]
            (log! (str "longbench/downloading url=" dataset-url
                       (when content-len (str " size=" content-len " bytes")))))
          (with-open [in (.body response)
                      out (io/output-stream tmp)]
            (io/copy in out :buffer-size 65536))
          (log! (str "longbench/download-saved path=" (.getPath tmp)
                     " bytes=" (.length tmp)))
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

;; --- Experiment config ---

(def ^:private config-defaults
  {:dataset/path   data-file
   :dataset/domain code-repo-domain
   :budgets        {}
   :execution      {:concurrency 3 :min-delay-ms 0 :repeats 1}
   :output/dir     "data/longbench/experiments/"})

(defn load-experiment-config
  "Load experiment config from an EDN file. Returns the parsed map."
  [path]
  (edn/read-string (slurp path)))

(defn validate-experiment-config
  "Validate required fields in experiment config.
   Returns {:ok config} or {:error message}."
  [config]
  (cond
    (not (string? (:experiment/name config)))
    {:error "Missing or invalid :experiment/name (must be a string)"}

    (not (seq (:arms config)))
    {:error "Missing or empty :arms (must be a non-empty vector)"}

    (not (every? :arm/name (:arms config)))
    {:error "Every arm must have :arm/name"}

    (not (every? :arm/type (:arms config)))
    {:error "Every arm must have :arm/type"}

    (not (get-in config [:model :provider]))
    {:error "Missing :model :provider"}

    (not (get-in config [:model :alias]))
    {:error "Missing :model :alias"}

    :else {:ok config}))

(defn resolve-config-defaults
  "Merge defaults for optional config fields."
  [config]
  (-> (merge-with (fn [default override]
                    (if (map? default)
                      (merge default override)
                      (or override default)))
                  config-defaults config)
      (update :execution #(merge (:execution config-defaults) %))))

(defn config-hash
  "Compute a reproducibility hash of the config. Excludes :output/dir."
  [config]
  (util/sha256-hex (pr-str (dissoc config :output/dir))))

(defn resolve-model-config
  "Resolve model provider and alias to concrete provider keyword and model ID."
  [{:keys [provider alias]}]
  {:provider-kw (llm/provider->kw provider)
   :model-id    (llm/model-alias->id alias)})

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

;; --- Arm runners ---

(defn run-pure-llm-arm
  "Pure-LLM arm: build prompt from question context, invoke LLM, extract answer.
   Returns {:prediction str|nil :response str :usage map :timing-ms long}."
  [{:keys [question invoke-llm]}]
  (let [start-ms (System/currentTimeMillis)
        raw-ctx  (:context question)
        ctx      (truncate-middle raw-ctx)
        _        (when (not= (count ctx) (count raw-ctx))
                   (log! (str "longbench/context-truncated"
                              " q=" (:_id question)
                              " original=" (count raw-ctx)
                              " truncated=" (count ctx))))
        prompt   (build-prompt (assoc question :context ctx))
        response (invoke-llm [{:role "user" :content prompt}])]
    {:prediction (extract-answer (:text response))
     :response   (:text response)
     :usage      (:usage response)
     :timing-ms  (- (System/currentTimeMillis) start-ms)}))

(defn resolve-arm-runner
  "Resolve an arm type keyword to its runner function. Throws on unknown type."
  [arm-type]
  (case arm-type
    :pure-llm run-pure-llm-arm
    (throw (ex-info (str "Unknown arm type: " arm-type
                         ". Known types: :pure-llm")
                    {:arm-type arm-type}))))

;; --- Per-question result JSONL ---

(def ^:private jsonl-lock (Object.))

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
  "Run arm runner for one question, record prediction in checkpoint and JSONL."
  [{:keys [question invoke-llm arm-runner checkpoint-atom cp-path jsonl-path
           total session-cost]}]
  (let [{:keys [prediction response usage]} (arm-runner {:question   question
                                                         :invoke-llm invoke-llm})
        correct (= prediction (:answer question))]
    (swap! session-cost + (get usage :cost-usd 0.0))
    (swap! checkpoint-atom assoc-in [:results (:_id question)]
           {:prediction prediction :correct correct :usage usage})
    (bench/checkpoint-write-latest! cp-path checkpoint-atom)
    (write-result-line! jsonl-path question prediction response)
    (log! (str "longbench/question-complete"
               " q=" (:_id question)
               " pred=" (or prediction "nil")
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
   {:keys [cp-path jsonl-path total budget concurrency min-delay-ms arm-runner]}]
  (let [session-cost (atom 0.0)
        stop-flag    (atom nil)
        error-atom   (atom nil)
        rate-gate    (atom 0)
        start-ms     (System/currentTimeMillis)
        arm-runner   (or arm-runner run-pure-llm-arm)
        shared       {:invoke-llm invoke-llm :checkpoint-atom checkpoint-atom
                      :arm-runner arm-runner
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
      (log! "Re-run the experiment to retry remaining questions."))
    {:results         (vec result-data)
     :aggregate       agg
     :total-usage     total-usage
     :run-id          run-id
     :checkpoint-path cp-path
     :stop-reason     stop-reason}))

;; --- Experiment orchestrator ---

(defn ensure-dataset!
  "Ensure dataset exists at the configured path. Downloads if missing. Returns path."
  [dataset-path]
  (if (.exists (io/file dataset-path))
    dataset-path
    (do (download-dataset!) dataset-path)))

(defn- dataset-hash
  "Compute SHA-256 of the dataset file for reproducibility metadata."
  [dataset-path]
  (sha256-hex (io/file dataset-path)))

(defn- git-sha
  "Get current project git SHA, or nil if not in a git repo."
  []
  (try
    (let [p (.start (ProcessBuilder. ["git" "rev-parse" "HEAD"]))]
      (str/trim (slurp (.getInputStream p))))
    (catch Exception _ nil)))

(defn build-experiment-metadata
  "Build metadata map for an experiment run."
  [config config-hash-str dataset-path model-config]
  {:git-sha       (git-sha)
   :config-hash   config-hash-str
   :dataset-hash  (dataset-hash dataset-path)
   :model         model-config
   :started-at    (java.util.Date.)
   :experiment    (:experiment/name config)})

(defn- experiment-output-dir
  "Build output directory path for one arm run."
  [base-dir experiment-name arm-name run-id]
  (str (io/file base-dir experiment-name arm-name run-id)))

(defn run-experiment-arm!
  "Run one arm of the experiment. Returns result map."
  [invoke-llm questions arm config metadata]
  (let [{:keys [concurrency min-delay-ms]} (:execution config)
        output-dir  (:output/dir config)
        arm-name    (:arm/name arm)
        arm-runner  (resolve-arm-runner (:arm/type arm))
        run-id      (bench/generate-run-id)
        out-dir     (experiment-output-dir output-dir
                                           (:experiment/name config)
                                           arm-name run-id)
        cp-path     (str (io/file out-dir "checkpoint.edn"))
        jsonl-path  (str (io/file out-dir "results.jsonl"))
        total       (count questions)
        budget      (let [b (:budgets config)]
                      {:max-questions  (:max-questions b)
                       :max-cost-usd   (:max-cost-usd b)
                       :stop-after-ms  (when-let [s (:stop-after-s b)]
                                         (* s 1000))})
        start-ms    (System/currentTimeMillis)
        checkpoint  (atom {:run-id   run-id
                           :metadata (merge metadata {:arm arm-name})
                           :results  {}})]
    (.mkdirs (io/file out-dir))
    (log! (str "longbench/arm-start arm=" arm-name
               " run-id=" run-id
               " questions=" total
               (when (> concurrency 1) (str " concurrency=" concurrency))))
    (let [stop-reason (run-pipeline! invoke-llm questions checkpoint
                                     {:cp-path cp-path :jsonl-path jsonl-path
                                      :total total :budget budget
                                      :concurrency concurrency
                                      :min-delay-ms min-delay-ms
                                      :arm-runner arm-runner})
          result      (finalize-run questions checkpoint cp-path
                                    run-id start-ms stop-reason)]
      (log! (str "longbench/arm-complete arm=" arm-name
                 " accuracy=" (when-let [o (:overall (:aggregate result))]
                                (format "%.1f" o)) "%"
                 " cost=$" (format "%.4f"
                                   (double (get-in result [:total-usage :cost-usd] 0.0)))))
      (assoc result :arm-name arm-name :output-dir out-dir))))

(defn- write-experiment-summary!
  "Write experiment summary EDN to the output directory."
  [output-dir experiment-name summary]
  (let [dir  (io/file output-dir experiment-name)
        path (io/file dir "summary.edn")]
    (.mkdirs dir)
    (spit path (pr-str summary))))

(defn generate-experiment-report
  "Generate a markdown report string from experiment results."
  [{:keys [metadata arm-results]}]
  (let [header (str "# Experiment: " (:experiment metadata) "\n\n"
                    "- Git SHA: " (or (:git-sha metadata) "unknown") "\n"
                    "- Config hash: " (:config-hash metadata) "\n"
                    "- Dataset hash: " (subs (or (:dataset-hash metadata) "") 0
                                             (min 16 (count (or (:dataset-hash metadata) "")))) "\n"
                    "- Model: " (get-in metadata [:model :model-id]) "\n"
                    "- Provider: " (name (get-in metadata [:model :provider-kw])) "\n"
                    "- Date: " (:started-at metadata) "\n")]
    (str header "\n"
         (str/join "\n"
                   (for [{:keys [arm-name aggregate total-usage]} arm-results]
                     (let [fmt (fn [v] (if v (format "%.1f%%" (double v)) "n/a"))]
                       (str "## Arm: " arm-name "\n\n"
                            "| Metric | Value |\n"
                            "|--------|-------|\n"
                            "| Overall | " (fmt (:overall aggregate)) " |\n"
                            "| Easy | " (fmt (:easy aggregate)) " |\n"
                            "| Hard | " (fmt (:hard aggregate)) " |\n"
                            "| Short | " (fmt (:short aggregate)) " |\n"
                            "| Medium | " (fmt (:medium aggregate)) " |\n"
                            "| Long | " (fmt (:long aggregate)) " |\n"
                            "| Questions | " (:total aggregate) " |\n"
                            "| Cost | $" (format "%.4f"
                                                 (double (get total-usage :cost-usd 0.0)))
                            " |\n")))))))

(defn run-experiment!
  "Run a complete experiment from config. Returns experiment result map."
  [config]
  (let [config       (resolve-config-defaults config)
        {:keys [error]} (validate-experiment-config config)]
    (when error (throw (ex-info error {:config config})))
    (let [cfg-hash    (config-hash config)
          model-cfg   (resolve-model-config (:model config))
          dataset-path (ensure-dataset! (:dataset/path config))
          questions    (load-code-repo-questions)
          invoke-llm   (llm/make-invoke-fn (:provider-kw model-cfg)
                                           {:model       (:model-id model-cfg)
                                            :temperature 0.1
                                            :max-tokens  128})
          metadata     (build-experiment-metadata config cfg-hash
                                                  dataset-path model-cfg)
          repeats      (get-in config [:execution :repeats] 1)]
      (when (= :claude-cli (:provider-kw model-cfg))
        (log! "longbench/param-deviation provider=claude-cli temperature=default max_tokens=default"))
      (log! (str "longbench/experiment-start"
                 " experiment=" (:experiment/name config)
                 " arms=" (count (:arms config))
                 " questions=" (count questions)
                 " repeats=" repeats))
      (let [arm-results (vec (for [arm     (:arms config)
                                   rep     (range repeats)]
                               (do (when (> repeats 1)
                                     (log! (str "longbench/repeat " (inc rep) "/" repeats)))
                                   (run-experiment-arm! invoke-llm questions
                                                        arm config metadata))))
            summary     {:metadata    metadata
                         :config-hash cfg-hash
                         :arm-results (mapv #(select-keys % [:arm-name :aggregate
                                                             :total-usage :run-id
                                                             :stop-reason :output-dir])
                                            arm-results)}
            report      (generate-experiment-report summary)]
        (write-experiment-summary! (:output/dir config)
                                   (:experiment/name config) summary)
        (log!)
        (log! report)
        (log! "longbench/experiment-complete")
        summary))))
