(ns noumenon.training-data
  "Generate training data for the query routing model from
   benchmark questions and agent session history."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.benchmark :as bench]
            [noumenon.query :as query]
            [noumenon.util :refer [log!]]))

;; --- Tokenization (simple word-level) ---

(defn tokenize
  "Split text into lowercase word tokens."
  [text]
  (->> (str/lower-case text)
       (re-seq #"[a-z0-9_\-]+")
       vec))

(defn build-vocab
  "Build a word->index vocabulary from a corpus of token sequences.
   Reserves index 0 for <PAD> and 1 for <UNK>."
  [token-seqs max-size]
  (let [freqs (->> token-seqs (mapcat identity) frequencies)
        sorted (->> freqs (sort-by val >) (take (- max-size 2)) (map key))]
    (into {"<PAD>" 0 "<UNK>" 1}
          (map-indexed (fn [i tok] [tok (+ i 2)]) sorted))))

(defn encode
  "Encode a token sequence using a vocabulary. Unknown tokens map to <UNK>=1."
  [vocab tokens]
  (mapv #(get vocab % 1) tokens))

;; --- Training data from benchmark ---

(defn- query-name->index
  "Build a map from query-name to output index."
  []
  (let [names (sort (query/list-query-names))]
    (into {} (map-indexed (fn [i n] [n i]) names))))

(defn benchmark-examples
  "Generate (question-text, query-name) pairs from benchmark questions."
  [db]
  (let [targets   (bench/pick-benchmark-targets db)
        questions (bench/resolve-question-params (bench/load-questions) targets)]
    (->> questions
         (filter :query-name)
         (mapv (fn [q] {:text       (:question q)
                        :query-name (:query-name q)})))))

;; --- Training data from agent sessions ---

(defn extract-query-patterns
  "Extract query names used in successful agent sessions from history."
  [history-path]
  (let [f (io/file history-path)]
    (when (.exists f)
      (let [history (edn/read-string (slurp f))]
        (->> history
             (filter #(= :improved (:outcome %)))
             (mapcat (fn [h]
                       (when-let [mod (:modification h)]
                         (when (= :examples (:target h))
                           (:examples mod))))))))))

;; --- Dataset assembly ---

(defn build-dataset
  "Build a complete training dataset.
   Returns {:examples [{:text str :tokens [int] :label int}...]
            :vocab {str int} :label-index {str int}}."
  [db {:keys [vocab-size] :or {vocab-size 2048}}]
  (let [examples   (benchmark-examples db)
        token-seqs (mapv (comp tokenize :text) examples)
        vocab      (build-vocab token-seqs vocab-size)
        labels     (query-name->index)]
    {:examples   (mapv (fn [ex toks]
                         {:text   (:text ex)
                          :tokens (encode vocab toks)
                          :label  (get labels (:query-name ex) 0)})
                       examples token-seqs)
     :vocab      vocab
     :label-index labels
     :n-classes  (count labels)}))

(defn save-dataset!
  "Save a dataset to EDN file."
  [dataset path]
  (.mkdirs (.getParentFile (io/file path)))
  (spit path (pr-str dataset))
  (log! (str "training-data: saved " (count (:examples dataset))
             " examples to " path)))

(defn load-dataset
  "Load a dataset from EDN file. Returns nil if not found."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))
