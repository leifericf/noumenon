(ns noumenon.pipeline-test
  (:require [clojure.test :refer [deftest is]]
            [noumenon.pipeline :as pipeline]))

(deftest run-concurrent-processes-all-items
  (let [seen      (atom [])
        stop-flag (atom nil)
        error     (atom nil)]
    (pipeline/run-concurrent!
     (range 10)
     {:concurrency 3
      :stop-flag stop-flag
      :error-atom error
      :process-item! #(swap! seen conj %)})
    (is (= (set (range 10)) (set @seen)))))

(deftest run-concurrent-stops-when-stop-flag-set
  (let [seen      (atom [])
        stop-flag (atom nil)
        error     (atom nil)]
    (pipeline/run-concurrent!
     (range 100)
     {:concurrency 4
      :stop-flag stop-flag
      :error-atom error
      :process-item! (fn [i]
                       (swap! seen conj i)
                       (when (= i 5)
                         (compare-and-set! stop-flag nil :max-questions)))})
    (is (= :max-questions @stop-flag))
    (is (<= (count @seen) 100))))

(deftest run-concurrent-reraises-first-error
  (let [stop-flag (atom nil)
        error     (atom nil)]
    (is (thrown? Exception
                 (pipeline/run-concurrent!
                  (range 10)
                  {:concurrency 2
                   :stop-flag stop-flag
                   :error-atom error
                   :process-item! (fn [i]
                                    (when (= i 3)
                                      (throw (ex-info "boom" {:i i}))))})))
    (is (= :error @stop-flag))))
