(ns noumenon.analyze-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.db :as db]))

;; --- Tier 0: Pure function tests ---

(deftest prompt-hash-stable
  (let [t "some template text"
        h1 (analyze/prompt-hash t)
        h2 (analyze/prompt-hash t)]
    (is (= h1 h2) "Same input produces same hash")
    (is (= 16 (count h1)) "Hash is 16 hex chars")))

(deftest prompt-hash-distinct
  (is (not= (analyze/prompt-hash "template A")
            (analyze/prompt-hash "template B"))))

(deftest render-prompt-substitutes
  (let [template "File: {{file-path}} Lang: {{lang}} Lines: {{line-count}}"
        result   (analyze/render-prompt template {:file-path "src/foo.clj"
                                                  :lang :clojure
                                                  :line-count 42
                                                  :content ""
                                                  :repo-name "myrepo"})]
    (is (str/includes? result "src/foo.clj"))
    (is (str/includes? result "clojure"))
    (is (str/includes? result "42"))))

(deftest render-prompt-missing-optional-keys
  (let [template "{{file-path}} {{lang}} {{line-count}} {{content}} {{repo-name}}"
        result   (analyze/render-prompt template {})]
    (is (string? result) "Does not throw on missing keys")))

(deftest parse-llm-response-well-formed
  (let [edn-str (pr-str {:summary "A file" :purpose "Does stuff"
                         :tags [:io :parsing] :complexity :simple
                         :layer :core :confidence 0.9
                         :segments [{:name "foo" :kind :function
                                     :line-start 1 :line-end 5}]})
        result  (analyze/parse-llm-response edn-str)]
    (is (= "A file" (:summary result)))
    (is (= "Does stuff" (:purpose result)))
    (is (= [:io :parsing] (:tags result)))
    (is (= :simple (:complexity result)))
    (is (= :core (:layer result)))
    (is (= 0.9 (:confidence result)))
    (is (= 1 (count (:segments result))))
    (is (= "foo" (-> result :segments first :name)))))

(deftest parse-llm-response-markdown-fences
  (let [edn-str (str "```edn\n" (pr-str {:summary "Test"}) "\n```")
        result  (analyze/parse-llm-response edn-str)]
    (is (= "Test" (:summary result)))))

(deftest parse-llm-response-malformed
  (is (nil? (analyze/parse-llm-response "not valid edn {")))
  (is (nil? (analyze/parse-llm-response "")))
  (is (nil? (analyze/parse-llm-response nil))))

(deftest parse-llm-response-unknown-keys-dropped
  (let [edn-str (pr-str {:summary "Test" :unknown-key "value" :bogus 42})
        result  (analyze/parse-llm-response edn-str)]
    (is (= "Test" (:summary result)))
    (is (not (contains? result :unknown-key)))
    (is (not (contains? result :bogus)))))

(deftest parse-llm-response-invalid-complexity-dropped
  (let [edn-str (pr-str {:summary "Test" :complexity :super-complex})
        result  (analyze/parse-llm-response edn-str)]
    (is (= "Test" (:summary result)))
    (is (not (contains? result :complexity)))))

(deftest parse-llm-response-strings-clamped
  (let [long-str (apply str (repeat 5000 "x"))
        edn-str  (pr-str {:summary long-str})
        result   (analyze/parse-llm-response edn-str)]
    (is (= 4096 (count (:summary result))))))

(deftest parse-llm-response-empty-collections-dropped
  (let [edn-str (pr-str {:summary "Test" :tags [] :patterns [] :segments []})
        result  (analyze/parse-llm-response edn-str)]
    (is (not (contains? result :tags)))
    (is (not (contains? result :patterns)))
    (is (not (contains? result :segments)))))

(deftest parse-llm-response-confidence-out-of-range
  (let [result1 (analyze/parse-llm-response (pr-str {:summary "X" :confidence 1.5}))
        result2 (analyze/parse-llm-response (pr-str {:summary "X" :confidence -0.1}))]
    (is (not (contains? result1 :confidence)))
    (is (not (contains? result2 :confidence)))))

(deftest analysis->tx-data-basic
  (let [analysis {:summary "Test summary" :purpose "Test purpose"
                  :complexity :simple :layer :core :confidence 0.8}
        tx-data  (analyze/analysis->tx-data "src/foo.clj" analysis
                                            {:model-version "test-model"
                                             :prompt-hash-val "abc123"
                                             :analyzer "test/0.1"})]
    (is (vector? tx-data))
    (is (>= (count tx-data) 2) "At least file entity and prov tx")
    (let [file-ent (first tx-data)]
      (is (= "src/foo.clj" (:file/path file-ent)))
      (is (= "Test summary" (:sem/summary file-ent)))
      (is (= :simple (:sem/complexity file-ent)))
      (is (= :core (:arch/layer file-ent)))
      (is (= 0.8 (:prov/confidence file-ent))))
    (let [prov (second tx-data)]
      (is (= :analyze (:tx/op prov)))
      (is (= :llm (:tx/source prov)))
      (is (= "test-model" (:prov/model-version prov)))
      (is (= "abc123" (:prov/prompt-hash prov)))
      (is (inst? (:prov/analyzed-at prov))))))

(deftest analysis->tx-data-with-segments
  (let [analysis {:summary "Test" :segments [{:name "bar" :kind :function
                                              :line-start 10 :line-end 20}]}
        tx-data  (analyze/analysis->tx-data "src/foo.clj" analysis
                                            {:model-version "m" :prompt-hash-val "h"
                                             :analyzer "a"})]
    (is (= 3 (count tx-data)) "file + prov + 1 segment")
    (let [seg (nth tx-data 2)]
      (is (= "bar" (:code/name seg)))
      (is (= [:file/path "src/foo.clj"] (:code/file seg)))
      (is (= :function (:code/kind seg))))))

(deftest analysis->tx-data-nil-returns-nil
  (is (nil? (analyze/analysis->tx-data "src/foo.clj" nil {})))
  (is (nil? (analyze/analysis->tx-data "src/foo.clj" {} {}))))

(deftest load-prompt-template-works
  (let [pt (analyze/load-prompt-template)]
    (is (string? (:template pt)))
    (is (str/includes? (:template pt) "{{file-path}}"))))

;; --- Tier 1: Integration tests ---

(defn- make-conn
  "Create an in-memory Datomic connection with schema."
  []
  (db/connect-and-ensure-schema :mem (str "analyze-test-" (random-uuid))))

(defn- import-stub-file!
  "Transact a minimal file entity with :file/lang."
  [conn path lang]
  (d/transact conn {:tx-data [{:file/path path :file/lang lang
                               :file/ext "clj" :file/size 100}]}))

(deftest files-needing-analysis-finds-unanalyzed
  (let [conn (make-conn)]
    (import-stub-file! conn "src/a.clj" :clojure)
    (import-stub-file! conn "src/b.clj" :clojure)
    (let [files (analyze/files-needing-analysis (d/db conn))]
      (is (= 2 (count files)))
      (is (= #{"src/a.clj" "src/b.clj"} (set (map :file/path files)))))))

(deftest files-needing-analysis-skips-analyzed
  (let [conn (make-conn)]
    (import-stub-file! conn "src/a.clj" :clojure)
    (import-stub-file! conn "src/b.clj" :clojure)
    ;; manually add :sem/summary to a.clj
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :sem/summary "Already done"}]})
    (let [files (analyze/files-needing-analysis (d/db conn))]
      (is (= 1 (count files)))
      (is (= "src/b.clj" (-> files first :file/path))))))

(deftest files-needing-analysis-skips-no-lang
  (let [conn (make-conn)]
    (import-stub-file! conn "src/a.clj" :clojure)
    ;; file with no lang
    (d/transact conn {:tx-data [{:file/path "LICENSE" :file/size 50}]})
    (let [files (analyze/files-needing-analysis (d/db conn))]
      (is (= 1 (count files)))
      (is (= "src/a.clj" (-> files first :file/path))))))

(deftest tx-data-round-trip
  (let [conn     (make-conn)
        _        (import-stub-file! conn "src/foo.clj" :clojure)
        analysis {:summary "A module" :purpose "Does things"
                  :complexity :moderate :layer :util :confidence 0.75
                  :tags [:io] :segments [{:name "init" :kind :function
                                          :line-start 1 :line-end 10}]}
        tx-data  (analyze/analysis->tx-data "src/foo.clj" analysis
                                            {:model-version "test-v1"
                                             :prompt-hash-val "hash123"
                                             :analyzer "test/0.1"})]
    (d/transact conn {:tx-data tx-data})
    (let [db  (d/db conn)
          ent (d/pull db '[*] [:file/path "src/foo.clj"])]
      (is (= "A module" (:sem/summary ent)))
      (is (= "Does things" (:sem/purpose ent)))
      (is (= :moderate (:sem/complexity ent)))
      (is (= :util (:arch/layer ent)))
      (is (= 0.75 (:prov/confidence ent)))
      (is (contains? (set (:sem/tags ent)) :io)))))

(deftest analyze-repo-with-mock-llm
  (let [conn       (make-conn)
        _          (import-stub-file! conn "src/a.clj" :clojure)
        _          (import-stub-file! conn "src/b.clj" :clojure)
        call-count (atom 0)
        mock-llm   (fn [_prompt]
                     (swap! call-count inc)
                     (pr-str {:summary "Mock summary" :complexity :simple
                              :layer :core :confidence 0.9}))]
    ;; Test the core pipeline: files-needing → parse → tx-data → transact
    (let [files (analyze/files-needing-analysis (d/db conn))]
      (is (= 2 (count files)))
      (doseq [f files]
        (let [raw      (mock-llm "test prompt")
              analysis (analyze/parse-llm-response raw)
              tx-data  (analyze/analysis->tx-data (:file/path f) analysis
                                                  {:model-version "test"
                                                   :prompt-hash-val "h"
                                                   :analyzer "test/0.1"})]
          (d/transact conn {:tx-data tx-data}))))
    ;; After analysis, files-needing-analysis should return empty
    (is (empty? (analyze/files-needing-analysis (d/db conn))))
    ;; Verify data was transacted
    (let [ent (d/pull (d/db conn) '[*] [:file/path "src/a.clj"])]
      (is (= "Mock summary" (:sem/summary ent))))))
