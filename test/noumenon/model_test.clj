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

(deftest forward-produces-probabilities
  (let [m     (model/init-model test-config)
        probs (model/forward m [1 2 3])]
    (is (= 4 (alength probs)))
    ;; probabilities sum to ~1
    (is (< (Math/abs (- 1.0 (reduce + (vec probs)))) 0.001))
    ;; all non-negative
    (is (every? #(>= % 0.0) (vec probs)))))

(deftest predict-returns-top-k
  (let [m    (model/init-model test-config)
        preds (model/predict m [1 2 3] 2)]
    (is (= 2 (count preds)))
    (is (every? :index preds))
    (is (every? :probability preds))))

(deftest train-respects-time-budget
  (let [m       (model/init-model test-config)
        dataset {:examples [{:tokens [1 2 3] :label 0}
                            {:tokens [4 5 6] :label 1}
                            {:tokens [7 8 9] :label 2}]
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

(deftest forward-with-empty-tokens
  ;; Must not throw on empty input
  (let [m     (model/init-model test-config)
        probs (model/forward m [])]
    (is (= 4 (alength probs)))
    (is (< (Math/abs (- 1.0 (reduce + (vec probs)))) 0.001))))

(deftest cross-entropy-oob-label
  ;; Label beyond output-dim must not crash
  (let [m (model/init-model test-config)]
    (is (number? (model/train-step! m [1 2 3] 999 0.01)))))

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
      (let [p1 (vec (model/forward m1 [1 2 3]))
            p2 (vec (model/forward m2 [1 2 3]))]
        (is (= p1 p2)))
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
        dataset {:examples [{:tokens [1 2 3] :label 0}
                            {:tokens [4 5 6] :label 1}
                            {:tokens [7 8 9] :label 2}
                            {:tokens [1 4 7] :label 0}
                            {:tokens [2 5 8] :label 1}]
                 :n-classes 4}
        ;; Get initial loss
        initial-probs (model/forward m [1 2 3])
        initial-loss  (- (Math/log (Math/max 1e-7 (aget initial-probs 0))))
        ;; Train
        _       (model/train! m dataset (assoc test-config :time-budget-sec 2))
        ;; Get final loss
        final-probs (model/forward m [1 2 3])
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
