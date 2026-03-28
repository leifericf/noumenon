(ns noumenon.model
  "Query routing model: predicts which Datalog query patterns to try
   for a given natural language question. Uses Deep Diamond for training
   and inference on CPU (DNNL backend)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.query :as query]
            [noumenon.util :refer [log!]])
  (:import [java.util Random]))

;; --- Config ---

(defn load-config []
  (query/load-edn-resource "model/config.edn"))

;; --- Pure-Clojure model (no Deep Diamond dependency at runtime) ---
;; This is a minimal feedforward network implemented in pure Clojure.
;; It serves as the baseline; Deep Diamond can be swapped in for GPU
;; acceleration when available.

(defn- random-matrix
  "Create a random weight matrix [rows cols] with Xavier initialization."
  ^doubles [^Random rng rows cols]
  (let [scale (Math/sqrt (/ 2.0 (+ rows cols)))
        arr   (double-array (* rows cols))]
    (dotimes [i (* rows cols)]
      (aset arr i (* scale (.nextGaussian rng))))
    arr))

(defn- random-vector
  "Create a zero-initialized bias vector."
  ^doubles [size]
  (double-array size))

(defn init-model
  "Initialize a 2-layer feedforward model.
   Returns {:w1 doubles :b1 doubles :w2 doubles :b2 doubles :config map}."
  [{:keys [embedding-dim hidden-dim output-dim] :as config}]
  (let [rng (Random. 42)]
    {:w1     (random-matrix rng embedding-dim hidden-dim)
     :b1     (random-vector hidden-dim)
     :w2     (random-matrix rng hidden-dim output-dim)
     :b2     (random-vector output-dim)
     :config config}))

;; --- Forward pass ---

(defn- bag-of-words
  "Average token indices into a fixed-size embedding vector.
   Simple but effective: each dimension is the mean token index
   binned into embedding-dim buckets."
  [tokens embedding-dim]
  (let [result (double-array embedding-dim)]
    (doseq [t tokens]
      (let [bucket (mod t embedding-dim)]
        (aset result bucket (+ (aget result bucket) 1.0))))
    (let [n (max 1.0 (double (count tokens)))]
      (dotimes [i embedding-dim]
        (aset result i (/ (aget result i) n))))
    result))

(defn- matmul-add
  "y = Wx + b. W is [out-dim x in-dim] stored row-major."
  ^doubles [^doubles w ^doubles b ^doubles x out-dim in-dim]
  (let [y (double-array out-dim)]
    (dotimes [i out-dim]
      (let [offset (* i in-dim)]
        (loop [j 0, sum (aget b i)]
          (if (>= j in-dim)
            (aset y i sum)
            (recur (inc j) (+ sum (* (aget w (+ offset j)) (aget x j))))))))
    y))

(defn- relu ^doubles [^doubles x]
  (let [y (double-array (alength x))]
    (dotimes [i (alength x)]
      (aset y i (Math/max 0.0 (aget x i))))
    y))

(defn- softmax ^doubles [^doubles x]
  (let [n   (alength x)
        max-val (areduce x i m Double/NEGATIVE_INFINITY (Math/max m (aget x i)))
        y   (double-array n)]
    (let [sum (loop [i 0, s 0.0]
                (if (>= i n) s
                    (let [v (Math/exp (- (aget x i) max-val))]
                      (aset y i v)
                      (recur (inc i) (+ s v)))))]
      (dotimes [i n]
        (aset y i (/ (aget y i) sum))))
    y))

(defn forward
  "Forward pass: tokens -> class probabilities."
  [{:keys [w1 b1 w2 b2 config]} tokens]
  (let [{:keys [embedding-dim hidden-dim output-dim]} config
        x  (bag-of-words tokens embedding-dim)
        h  (relu (matmul-add w1 b1 x hidden-dim embedding-dim))
        z  (matmul-add w2 b2 h output-dim hidden-dim)]
    (softmax z)))

(defn predict
  "Predict top-k query indices for a token sequence."
  [model tokens k]
  (let [probs    (forward model tokens)
        indexed  (map-indexed vector probs)
        top-k    (->> indexed (sort-by second >) (take k))]
    (mapv (fn [[idx prob]] {:index idx :probability prob}) top-k)))

(defn- index->query-name
  "Build a reverse map from output index to query name."
  []
  (let [names (sort (query/list-query-names))]
    (into {} (map-indexed (fn [i n] [i n]) names))))

(defn suggest-queries
  "Given a question string, return the top-k most relevant named query suggestions.
   Uses the vocab stored with the model for consistent tokenization.
   Returns a seq of {:query-name str :probability double}, or nil if no model is available."
  [model question k]
  (when (and model (:vocab model))
    (let [tokens  (->> (str/lower-case question) (re-seq #"[a-z0-9_\-]+") vec)
          encoded (mapv #(get (:vocab model) % 1) tokens) ;; 1 = <UNK>
          preds   (predict model encoded k)
          idx->name (index->query-name)]
      (->> preds
           (keep (fn [{:keys [index probability]}]
                   (when-let [qname (idx->name index)]
                     {:query-name qname :probability probability})))))))

;; --- Training ---

(defn- cross-entropy-loss
  "Compute cross-entropy loss for a single example."
  ^double [^doubles probs ^long label]
  (if (or (neg? label) (>= label (alength probs)))
    10.0 ;; max penalty for out-of-range labels
    (- (Math/log (Math/max 1e-7 (aget probs label))))))

(defn- numerical-gradient
  "Compute numerical gradient of loss w.r.t. a parameter array.
   Slow but correct — used for small models."
  [model param-key tokens label epsilon]
  (let [^doubles params (get model param-key)
        grad            (double-array (alength params))]
    (dotimes [i (alength params)]
      (let [orig (aget params i)]
        ;; f(x + eps)
        (aset params i (+ orig epsilon))
        (let [loss-plus (cross-entropy-loss (forward model tokens) label)]
          ;; f(x - eps)
          (aset params i (- orig epsilon))
          (let [loss-minus (cross-entropy-loss (forward model tokens) label)]
            (aset grad i (/ (- loss-plus loss-minus) (* 2.0 epsilon)))
            (aset params i orig)))))
    grad))

(defn- sgd-update!
  "In-place SGD update: params -= lr * gradient."
  [^doubles params ^doubles grad ^double lr]
  (dotimes [i (alength params)]
    (aset params i (- (aget params i) (* lr (aget grad i))))))

(defn train-step!
  "Train on a single example. Returns loss."
  [model tokens label lr]
  (let [probs (forward model tokens)
        loss  (cross-entropy-loss probs label)]
    ;; Numerical gradient for each parameter
    (doseq [k [:w1 :b1 :w2 :b2]]
      (let [grad (numerical-gradient model k tokens label 1e-4)]
        (sgd-update! (get model k) grad lr)))
    loss))

(defn train!
  "Train the model for a time budget. Returns training stats.
   Like autoresearch: fixed time budget, model modifies in-place."
  [model dataset {:keys [time-budget-sec learning-rate batch-size]
                  :or   {time-budget-sec 300 learning-rate 0.001 batch-size 32}}]
  (let [examples   (:examples dataset)
        n          (count examples)
        start-ms   (System/currentTimeMillis)
        budget-ms  (* time-budget-sec 1000)]
    (log! (str "model/train: " n " examples, "
               time-budget-sec "s budget, lr=" learning-rate))
    (when (zero? n)
      (log! "model/train: no examples, skipping"))
    (if (zero? n)
      {:epochs 0 :steps 0 :avg-loss 0.0 :elapsed-ms 0}
      (loop [epoch 0, total-loss 0.0, steps 0]
        (let [elapsed (- (System/currentTimeMillis) start-ms)]
          (if (>= elapsed budget-ms)
            (do (log! (str "model/train: " epoch " epochs, " steps " steps, "
                           "avg loss=" (format "%.4f" (if (pos? steps) (/ total-loss steps) 0.0))))
                {:epochs epoch :steps steps
                 :avg-loss (if (pos? steps) (/ total-loss steps) 0.0)
                 :elapsed-ms elapsed})
            (let [indices (shuffle (range n))
                  batch   (take batch-size indices)
                  losses  (mapv (fn [idx]
                                  (let [{:keys [tokens label]} (nth examples idx)]
                                    (train-step! model tokens label learning-rate)))
                                batch)]
              (recur (inc epoch)
                     (+ total-loss (reduce + losses))
                     (+ steps (count batch))))))))))

;; --- Evaluation ---

(defn evaluate
  "Evaluate model accuracy on a dataset. Returns {:accuracy double :top3-accuracy double}."
  [model dataset]
  (let [examples (:examples dataset)]
    (if (empty? examples)
      {:accuracy 0.0 :top3-accuracy 0.0}
      (let [results (mapv (fn [{:keys [tokens label]}]
                            (let [preds (predict model tokens 3)]
                              {:correct? (= label (:index (first preds)))
                               :in-top3? (some #(= label (:index %)) preds)}))
                          examples)
            n       (count results)]
        {:accuracy      (/ (double (count (filter :correct? results))) n)
         :top3-accuracy (/ (double (count (filter :in-top3? results))) n)}))))

;; --- Persistence ---

(defn save-model!
  "Save model weights and vocab to EDN file."
  [model path]
  (.mkdirs (.getParentFile (io/file path)))
  (spit path (pr-str {:w1     (vec (:w1 model))
                      :b1     (vec (:b1 model))
                      :w2     (vec (:w2 model))
                      :b2     (vec (:b2 model))
                      :config (:config model)
                      :vocab  (:vocab model)}))
  (log! (str "model: saved to " path)))

(defn load-model
  "Load model weights and vocab from EDN file. Returns nil if not found."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (let [{:keys [w1 b1 w2 b2 config vocab]} (edn/read-string (slurp f))]
        {:w1     (double-array w1)
         :b1     (double-array b1)
         :w2     (double-array w2)
         :b2     (double-array b2)
         :config config
         :vocab  vocab}))))

(defn load-pretrained
  "Load pre-trained model from classpath resource. Returns nil if not bundled."
  []
  (when-let [url (io/resource "model/weights.edn")]
    (let [{:keys [w1 b1 w2 b2 config vocab]} (edn/read-string (slurp url))]
      {:w1 (double-array w1) :b1 (double-array b1)
       :w2 (double-array w2) :b2 (double-array b2)
       :config config :vocab vocab})))

(defn load-best-model
  "Load the best available model: local trained > bundled pretrained > nil."
  []
  (or (load-model "data/models/latest.edn")
      (load-pretrained)))
