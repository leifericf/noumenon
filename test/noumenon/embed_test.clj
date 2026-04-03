(ns noumenon.embed-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.embed :as embed]))

;; --- Tokenizer ---

(deftest tokenize-basic
  (is (= ["hello" "world"] (embed/tokenize "Hello, World!")))
  (is (= ["clojure" "function"] (embed/tokenize "a Clojure function")))
  (testing "removes stopwords and short tokens"
    (is (= ["cat"] (embed/tokenize "the a is cat")))
    (is (= [] (embed/tokenize "a I")))))

(deftest tokenize-code-terms
  (testing "preserves hyphens and underscores"
    (is (= ["my-function" "some_var"] (embed/tokenize "my-function some_var")))
    (is (= ["file-path" "db-name"] (embed/tokenize "file-path and db-name")))))

;; --- Vocabulary ---

(deftest build-vocab-basic
  (let [tokens [["alpha" "beta" "gamma"] ["alpha" "beta"] ["alpha"]]
        vocab  (embed/build-vocab tokens 10)]
    (is (= 3 (count vocab)))
    (is (zero? (vocab "alpha")) "most frequent term gets index 0")
    (is (= 1 (vocab "beta")))))

(deftest build-vocab-max-size
  (let [tokens [["aa" "bb" "cc" "dd" "ee"]]
        vocab  (embed/build-vocab tokens 3)]
    (is (= 3 (count vocab)))))

;; --- IDF ---

(deftest compute-idf-basic
  (let [vocab  {"common" 0 "rare" 1}
        tokens [#{"common" "rare"} #{"common"} #{"common"}]
        idf    (embed/compute-idf vocab (mapv vec tokens))]
    (is (< (aget idf 0) (aget idf 1))
        "rare term should have higher IDF than common term")))

;; --- TF-IDF vectors ---

(deftest tfidf-vec-basic
  (let [vocab {"alpha" 0 "beta" 1 "gamma" 2}
        idf   (double-array [1.0 2.0 3.0])
        v     (embed/tfidf-vec vocab idf ["alpha" "alpha" "beta"])]
    (is (double? (aget v 0)))
    (is (pos? (aget v 0)) "alpha should have nonzero weight")
    (is (pos? (aget v 1)) "beta should have nonzero weight")
    (is (zero? (aget v 2)) "gamma not in doc, should be zero")))

;; --- Cosine similarity ---

(deftest cosine-identical-vectors
  (let [v (double-array [1.0 2.0 3.0])]
    (is (< (Math/abs (- 1.0 (embed/cosine-similarity v v))) 1e-9))))

(deftest cosine-orthogonal-vectors
  (let [a (double-array [1.0 0.0 0.0])
        b (double-array [0.0 1.0 0.0])]
    (is (< (Math/abs (embed/cosine-similarity a b)) 1e-9))))

(deftest cosine-zero-vector
  (let [a (double-array [1.0 2.0])
        z (double-array [0.0 0.0])]
    (is (= 0.0 (embed/cosine-similarity a z)))))

(deftest cosine-similar-vectors
  (let [a (double-array [1.0 1.0 0.0])
        b (double-array [1.0 1.0 1.0])]
    (is (< 0.5 (embed/cosine-similarity a b) 1.0))))

;; --- Top-k ---

(deftest top-k-basic
  (let [query  (double-array [1.0 0.0])
        entries [{:path "a.clj" :vec (double-array [1.0 0.0])}
                 {:path "b.clj" :vec (double-array [0.0 1.0])}
                 {:path "c.clj" :vec (double-array [0.7 0.7])}]
        results (embed/top-k query entries 2)]
    (is (= 2 (count results)))
    (is (= "a.clj" (:path (first results))))
    (is (every? :score results))))

;; --- Build index (unit, no Datomic) ---

(deftest build-index-from-docs
  (testing "end-to-end vectorization pipeline"
    (let [token-lists [["clojure" "function" "data" "transform"]
                       ["javascript" "react" "component" "render"]
                       ["clojure" "data" "pipeline" "query"]]
          vocab       (embed/build-vocab token-lists)
          idf         (embed/compute-idf vocab token-lists)
          vecs        (mapv #(embed/tfidf-vec vocab idf %) token-lists)
          ;; clojure docs (0 and 2) should be more similar to each other than to JS doc (1)
          sim-01      (embed/cosine-similarity (nth vecs 0) (nth vecs 1))
          sim-02      (embed/cosine-similarity (nth vecs 0) (nth vecs 2))]
      (is (> sim-02 sim-01)
          "Clojure docs should be more similar to each other than to JS doc"))))

;; --- Search ---

(deftest search-returns-ranked-results
  (let [token-lists [["agent" "loop" "query" "datalog"]
                     ["llm" "provider" "api" "http"]
                     ["file" "import" "dependency" "graph"]]
        vocab       (embed/build-vocab token-lists)
        idf         (embed/compute-idf vocab token-lists)
        entries     [{:kind :file :path "agent.clj" :text "agent loop query datalog"
                      :vec (embed/tfidf-vec vocab idf (nth token-lists 0))}
                     {:kind :file :path "llm.clj" :text "llm provider api http"
                      :vec (embed/tfidf-vec vocab idf (nth token-lists 1))}
                     {:kind :file :path "imports.clj" :text "file import dependency graph"
                      :vec (embed/tfidf-vec vocab idf (nth token-lists 2))}]
        index       {:vocab vocab :idf idf :entries entries}
        results     (embed/search index "how does the agent query loop work?")]
    (is (seq results))
    (is (= "agent.clj" (:path (first results))))
    (is (every? #(nil? (:vec %)) results) "search results should not include raw vectors")))

(deftest search-nil-index
  (is (nil? (embed/search nil "anything"))))

(deftest search-empty-index
  (is (nil? (embed/search {:vocab {} :idf (double-array 0) :entries []} "anything"))))
