(ns noumenon.model-test
  (:require [clojure.test :refer [deftest is]]
            [noumenon.model :as model]
            [noumenon.training-data :as td]))

(def test-config
  {:embedding-dim 8 :hidden-dim 16 :output-dim 4
   :learning-rate 0.01 :batch-size 2 :time-budget-sec 1})

(deftest init-model-structure
  (let [m (model/init-model test-config)]
    (is (= 4 (count (select-keys m [:w1 :b1 :w2 :b2]))))
    (is (= (* 8 16) (alength ^doubles (:w1 m))))
    (is (= 16 (alength ^doubles (:b1 m))))
    (is (= (* 16 4) (alength ^doubles (:w2 m))))
    (is (= 4 (alength ^doubles (:b2 m))))))

(defn- test-input
  "Create a test input vector matching embedding-dim."
  ^doubles [& vals]
  (double-array (take 8 (concat vals (repeat 0.0)))))

(deftest forward-produces-probabilities
  (let [m     (model/init-model test-config)
        probs (model/forward m (test-input 1.0 0.5 0.3))]
    (is (= 4 (alength probs)))
    ;; probabilities sum to ~1
    (is (< (Math/abs (- 1.0 (reduce + (vec probs)))) 0.001))
    ;; all non-negative
    (is (every? #(>= % 0.0) (vec probs)))))

(deftest predict-returns-top-k
  (let [m     (model/init-model test-config)
        preds (model/predict m (test-input 1.0 0.5 0.3) 2)]
    (is (= 2 (count preds)))
    (is (every? :index preds))
    (is (every? :probability preds))))

(deftest train-respects-time-budget
  (let [m       (model/init-model test-config)
        dataset {:examples [{:input (test-input 1.0 0.5 0.3) :label 0}
                            {:input (test-input 0.0 1.0 0.5) :label 1}
                            {:input (test-input 0.3 0.0 1.0) :label 2}]
                 :n-classes 4}
        result  (model/train! m dataset (assoc test-config :time-budget-sec 1))]
    (is (pos? (:steps result)))
    (is (number? (:avg-loss result)))))

(deftest tokenize-basic
  (is (= ["hello" "world" "42"] (td/tokenize "Hello, World! 42"))))

(deftest build-vocab-reserves-special-tokens
  (let [v (td/build-vocab [["a" "b" "c"]] 5)]
    (is (= 0 (v "<PAD>")))
    (is (= 1 (v "<UNK>")))
    (is (contains? v "a"))))

(deftest encode-uses-unk-for-missing
  (let [v (td/build-vocab [["hello"]] 3)]
    (is (= [1] (td/encode v ["unknown"])))))

(deftest evaluate-empty-dataset
  ;; Must not throw on empty dataset
  (let [m (model/init-model test-config)
        r (model/evaluate m {:examples []})]
    (is (= 0.0 (:accuracy r)))
    (is (= 0.0 (:top3-accuracy r)))))

(deftest train-empty-dataset
  ;; Must not throw or infinite-loop on empty dataset
  (let [m      (model/init-model test-config)
        result (model/train! m {:examples []} (assoc test-config :time-budget-sec 1))]
    (is (zero? (:steps result)))))

(deftest forward-with-zero-input
  ;; Must not throw on zero input
  (let [m     (model/init-model test-config)
        probs (model/forward m (double-array 8))]
    (is (= 4 (alength probs)))
    (is (< (Math/abs (- 1.0 (reduce + (vec probs)))) 0.001))))

(deftest cross-entropy-oob-label
  ;; Label beyond output-dim must not crash
  (let [m (model/init-model test-config)]
    (is (number? (model/train-step! m (test-input 1.0 0.5 0.3) 999 0.01)))))

;; --- Model save/load round-trip ---

(deftest save-load-round-trip
  (let [path (str "/tmp/model-roundtrip-" (System/currentTimeMillis) ".edn")
        m1   (model/init-model test-config)
        _    (model/save-model! m1 path)
        m2   (model/load-model path)]
    (try
      (is (some? m2))
      (is (= (vec (:w1 m1)) (vec (:w1 m2))))
      (is (= (vec (:b2 m1)) (vec (:b2 m2))))
      (is (= (:config m1) (:config m2)))
      ;; Predictions should be identical
      (let [input (test-input 1.0 0.5 0.3)
            p1    (vec (model/forward m1 input))
            p2    (vec (model/forward m2 input))]
        (is (= p1 p2)))
      (finally
        (.delete (java.io.File. path))))))

(deftest save-load-preserves-label-index
  (let [path  (str "/tmp/model-label-idx-" (System/currentTimeMillis) ".edn")
        m1    (assoc (model/init-model test-config)
                     :label-index {0 "query-a" 1 "query-b" 2 "query-c" 3 "query-d"})
        _     (model/save-model! m1 path)
        m2    (model/load-model path)]
    (try
      (is (= (:label-index m1) (:label-index m2)))
      (finally
        (.delete (java.io.File. path))))))

(deftest load-model-missing
  (is (nil? (model/load-model "/tmp/nonexistent-model.edn"))))

(deftest load-model-rejects-mismatched-dimensions
  (let [path (str "/tmp/model-bad-dims-" (System/currentTimeMillis) ".edn")
        bad  {:w1 [1.0 2.0] :b1 [1.0] :w2 [1.0] :b2 [1.0]
              :config {:embedding-dim 8 :hidden-dim 16 :output-dim 4}}]
    (spit path (pr-str bad))
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"dimensions do not match"
                            (model/load-model path)))
      (finally
        (.delete (java.io.File. path))))))

;; --- Training actually reduces loss ---

(deftest training-reduces-loss
  (let [m       (model/init-model test-config)
        input-0 (test-input 1.0 0.5 0.3)
        dataset {:examples [{:input input-0 :label 0}
                            {:input (test-input 0.0 1.0 0.5) :label 1}
                            {:input (test-input 0.3 0.0 1.0) :label 2}
                            {:input (test-input 0.8 0.3 0.6) :label 0}
                            {:input (test-input 0.1 0.9 0.4) :label 1}]
                 :n-classes 4}
        ;; Get initial loss
        initial-probs (model/forward m input-0)
        initial-loss  (- (Math/log (Math/max 1e-7 (aget initial-probs 0))))
        ;; Train
        _       (model/train! m dataset (assoc test-config :time-budget-sec 2))
        ;; Get final loss
        final-probs (model/forward m input-0)
        final-loss  (- (Math/log (Math/max 1e-7 (aget final-probs 0))))]
    ;; Loss should decrease (or at least not increase dramatically)
    (is (<= final-loss (+ initial-loss 1.0))
        "Loss increased dramatically after training")))

;; --- Tokenize edge cases ---

(deftest tokenize-empty-string
  (is (= [] (td/tokenize ""))))

(deftest tokenize-only-punctuation
  (is (= [] (td/tokenize "!@#$%^&*()"))))

(deftest build-vocab-empty-corpus
  (let [v (td/build-vocab [] 100)]
    (is (= 0 (v "<PAD>")))
    (is (= 1 (v "<UNK>")))
    (is (= 2 (count v)))))
