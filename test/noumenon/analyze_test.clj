(ns noumenon.analyze-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.db :as db]))

;; --- Tier 0: Pure function tests ---

(deftest repo-name-basic
  (is (= "myrepo" (analyze/repo-name "/home/user/myrepo"))))

(deftest repo-name-trailing-slash
  (is (= "myrepo" (analyze/repo-name "/home/user/myrepo/")))
  (is (= "myrepo" (analyze/repo-name "/home/user/myrepo///"))))

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

(deftest render-prompt-escapes-template-vars-in-content
  (let [template "Repo: {{repo-name}} Content: {{content}}"
        result   (analyze/render-prompt template
                                        {:content   "payload {{repo-name}} injection"
                                         :repo-name "real-repo"})]
    (is (str/includes? result "real-repo") "Real repo-name is substituted")
    (is (not (str/includes? result "payload real-repo"))
        "Content template vars must not be substituted as template variables")
    (is (str/includes? result "{ {repo-name}")
        "Template metacharacters in content are escaped")))

(deftest render-prompt-wraps-content-in-delimiters
  (let [template "{{content}}"
        result   (analyze/render-prompt template {:content "hello"})]
    (is (str/includes? result "<file-content>"))
    (is (str/includes? result "</file-content>"))
    (is (str/includes? result "hello"))))

(deftest parse-llm-response-well-formed
  (let [edn-str (pr-str {:summary "A file" :purpose "Does stuff"
                         :tags [:io :parsing] :complexity :simple
                         :layer :core :category :backend :confidence 0.9
                         :segments [{:name "foo" :kind :function
                                     :line-start 1 :line-end 5
                                     :args "[x y]" :returns "int"
                                     :visibility :public :docstring "Does foo"
                                     :deprecated true :complexity :simple
                                     :smells [:magic-numbers]
                                     :purpose "Foos the bar"
                                     :safety-concerns [:sql-injection]
                                     :error-handling :basic}]})
        result  (analyze/parse-llm-response edn-str)
        seg     (-> result :segments first)]
    (is (= "A file" (:summary result)))
    (is (= "Does stuff" (:purpose result)))
    (is (= [:io :parsing] (:tags result)))
    (is (= :simple (:complexity result)))
    (is (= :core (:layer result)))
    (is (= :backend (:category result)))
    (is (= 0.9 (:confidence result)))
    (is (= 1 (count (:segments result))))
    (is (= "foo" (:name seg)))
    (is (= "[x y]" (:args seg)))
    (is (= "int" (:returns seg)))
    (is (= :public (:visibility seg)))
    (is (= "Does foo" (:docstring seg)))
    (is (true? (:deprecated seg)))
    (is (= :simple (:complexity seg)))
    (is (= [:magic-numbers] (:smells seg)))
    (is (= "Foos the bar" (:purpose seg)))
    (is (= [:sql-injection] (:safety-concerns seg)))
    (is (= :basic (:error-handling seg)))))

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

(deftest parse-llm-response-category-validated
  (let [result (analyze/parse-llm-response (pr-str {:summary "X" :category :backend}))]
    (is (= :backend (:category result))))
  (let [result (analyze/parse-llm-response (pr-str {:summary "X" :category :bogus}))]
    (is (not (contains? result :category)))))

(deftest parse-llm-response-segment-signature-fields
  (let [result (analyze/parse-llm-response
                (pr-str {:summary "X"
                         :segments [{:name "f" :args "[x]" :returns "int"
                                     :visibility :private :docstring "doc"}]}))
        seg    (-> result :segments first)]
    (is (= "[x]" (:args seg)))
    (is (= "int" (:returns seg)))
    (is (= :private (:visibility seg)))
    (is (= "doc" (:docstring seg)))))

(deftest parse-llm-response-segment-blank-strings-dropped
  (let [result (analyze/parse-llm-response
                (pr-str {:summary "X"
                         :segments [{:name "f" :args "" :returns "  "
                                     :docstring "" :purpose ""}]}))
        seg    (-> result :segments first)]
    (is (not (contains? seg :args)))
    (is (not (contains? seg :returns)))
    (is (not (contains? seg :docstring)))
    (is (not (contains? seg :purpose)))))

(deftest parse-llm-response-segment-quality-fields
  (let [result (analyze/parse-llm-response
                (pr-str {:summary "X"
                         :segments [{:name "f" :complexity :moderate
                                     :smells [:deep-nesting :too-many-params]
                                     :purpose "Does stuff"
                                     :safety-concerns [:xss :race-condition]
                                     :error-handling :robust}]}))
        seg    (-> result :segments first)]
    (is (= :moderate (:complexity seg)))
    (is (= [:deep-nesting :too-many-params] (:smells seg)))
    (is (= "Does stuff" (:purpose seg)))
    (is (= [:xss :race-condition] (:safety-concerns seg)))
    (is (= :robust (:error-handling seg)))))

(deftest parse-llm-response-segment-invalid-enums-filtered
  (let [result (analyze/parse-llm-response
                (pr-str {:summary "X"
                         :segments [{:name "f"
                                     :visibility :bogus
                                     :complexity :ultra
                                     :smells [:deep-nesting :fake-smell :too-many-params]
                                     :safety-concerns [:xss :made-up]
                                     :error-handling :amazing
                                     :deprecated false}]}))
        seg    (-> result :segments first)]
    (is (not (contains? seg :visibility)))
    (is (not (contains? seg :complexity)))
    (is (= [:deep-nesting :too-many-params] (:smells seg)))
    (is (= [:xss] (:safety-concerns seg)))
    (is (not (contains? seg :error-handling)))
    (is (not (contains? seg :deprecated)))))

(deftest parse-llm-response-segment-new-kinds
  (let [result (analyze/parse-llm-response
                (pr-str {:summary "X"
                         :segments [{:name "Foo" :kind :class}
                                    {:name "Bar" :kind :interface}
                                    {:name "baz" :kind :method}
                                    {:name "C" :kind :constant}
                                    {:name "U" :kind :union}]}))
        kinds  (mapv :kind (:segments result))]
    (is (= [:class :interface :method :constant :union] kinds))))

(deftest analysis->tx-data-basic
  (let [analysis {:summary "Test summary" :purpose "Test purpose"
                  :complexity :simple :layer :core :category :backend
                  :confidence 0.8}
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
      (is (= :backend (:sem/category file-ent)))
      (is (= 0.8 (:prov/confidence file-ent))))
    (let [prov (second tx-data)]
      (is (= :analyze (:tx/op prov)))
      (is (= :llm (:tx/source prov)))
      (is (= "test-model" (:prov/model-version prov)))
      (is (= "abc123" (:prov/prompt-hash prov)))
      (is (inst? (:prov/analyzed-at prov))))))

(deftest analysis->tx-data-with-segments
  (let [analysis {:summary "Test"
                  :segments [{:name "bar" :kind :function
                              :line-start 10 :line-end 20
                              :args "[x y]" :returns "int"
                              :visibility :public :docstring "Does bar"
                              :deprecated true :complexity :moderate
                              :smells [:deep-nesting]
                              :purpose "Bars the baz"
                              :safety-concerns [:xss]
                              :error-handling :basic}]}
        tx-data  (analyze/analysis->tx-data "src/foo.clj" analysis
                                            {:model-version "m" :prompt-hash-val "h"
                                             :analyzer "a"})]
    (is (= 3 (count tx-data)) "file + prov + 1 segment")
    (let [seg (nth tx-data 2)]
      (is (= "bar" (:code/name seg)))
      (is (= [:file/path "src/foo.clj"] (:code/file seg)))
      (is (= :function (:code/kind seg)))
      (is (= "[x y]" (:code/args seg)))
      (is (= "int" (:code/returns seg)))
      (is (= :public (:code/visibility seg)))
      (is (= "Does bar" (:code/docstring seg)))
      (is (true? (:code/deprecated? seg)))
      (is (= :moderate (:code/complexity seg)))
      (is (= [:deep-nesting] (:code/smells seg)))
      (is (= "Bars the baz" (:code/purpose seg)))
      (is (= [:xss] (:code/safety-concerns seg)))
      (is (= :basic (:code/error-handling seg))))))

(deftest analysis->tx-data-nil-returns-nil
  (is (nil? (analyze/analysis->tx-data "src/foo.clj" nil {})))
  (is (nil? (analyze/analysis->tx-data "src/foo.clj" {} {}))))

(deftest load-prompt-template-works
  (let [pt (analyze/load-prompt-template)]
    (is (string? (:template pt)))
    (is (str/includes? (:template pt) "{{file-path}}"))))

;; --- Tier 0: git-show ---

(deftest git-show-returns-file-content
  (let [content (analyze/git-show (System/getProperty "user.dir") "deps.edn")]
    (is (string? content))
    (is (not (str/blank? content)))
    (is (str/includes? content "deps"))))

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

(deftest tx-data-round-trip-with-rich-segment
  (let [conn     (make-conn)
        _        (import-stub-file! conn "src/foo.clj" :clojure)
        analysis {:summary "A module" :purpose "Does things" :category :backend
                  :complexity :moderate :layer :util :confidence 0.75
                  :tags [:io]
                  :segments [{:name "init" :kind :function
                              :line-start 1 :line-end 10
                              :args "[db opts]" :visibility :public
                              :docstring "Initialize the system"
                              :complexity :simple :purpose "Sets up DB connection"
                              :error-handling :robust}]}
        tx-data  (analyze/analysis->tx-data "src/foo.clj" analysis
                                            {:model-version "test-v1"
                                             :prompt-hash-val "hash123"
                                             :analyzer "test/0.1"})]
    (d/transact conn {:tx-data tx-data})
    (let [db   (d/db conn)
          file (d/pull db '[*] [:file/path "src/foo.clj"])
          seg  (d/pull db '[*] [:code/file+name [(:db/id file) "init"]])]
      (is (= :backend (:sem/category file)))
      (is (= "init" (:code/name seg)))
      (is (= "[db opts]" (:code/args seg)))
      (is (= :public (:code/visibility seg)))
      (is (= "Initialize the system" (:code/docstring seg)))
      (is (= :simple (:code/complexity seg)))
      (is (= "Sets up DB connection" (:code/purpose seg)))
      (is (= :robust (:code/error-handling seg))))))

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
