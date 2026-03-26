(ns noumenon.cli
  (:require [clojure.string :as str]
            [noumenon.llm :as llm]))

;; --- Coercion / validation registry ---

(defn- try-parse-int [s]
  (try (Integer/parseInt s) (catch Exception _ nil)))

(defn- try-parse-double [s]
  (try (Double/parseDouble s) (catch Exception _ nil)))

(def ^:private parser-registry
  {:string      {:coerce identity
                 :valid? (constantly true)}
   :pos-int     {:coerce try-parse-int
                 :valid? pos?}
   :non-neg-int {:coerce try-parse-int
                 :valid? #(>= % 0)}
   :pos-double  {:coerce try-parse-double
                 :valid? pos?}
   :range-int   {:coerce try-parse-int
                 :valid? (fn [n {:keys [min max]}]
                           (<= min n max))}})

(defn- parse-value
  "Parse and validate one value using parser-registry.
   Returns {:ok v} or {:error kw :value raw}."
  [spec raw]
  (let [{:keys [coerce valid?]} (parser-registry (:parse spec))
        value (coerce raw)
        in-range? (when value
                    (if (= :range-int (:parse spec))
                      (valid? value spec)
                      (valid? value)))]
    (if in-range?
      {:ok value}
      {:error (:error-invalid spec) :value raw})))

(defn- provider-set
  "Build a provider set that supports canonical values plus optional aliases."
  [& values]
  (set values))

(def ^:private benchmark-valid-providers
  (provider-set "glm" "claude" "claude-api" "claude-cli"))

(def ^:private longbench-valid-providers
  (provider-set "glm" "claude" "claude-api" "claude-cli"))

(def ^:private agent-valid-providers
  (provider-set "glm" "claude" "claude-api" "claude-cli"))

;; --- Shared flag specs ---

(def ^:private common-flags
  [{:flag "--model" :key :model :parse :string
    :error-missing :missing-model-value}
   {:flag "--provider" :key :provider :parse :string
    :error-invalid :invalid-provider :error-missing :missing-provider-value}
   {:flag "--max-cost" :key :max-cost :parse :pos-double
    :error-invalid :invalid-max-cost :error-missing :missing-max-cost-value}
   {:flag "--db-dir" :key :db-dir :parse :string
    :error-missing :missing-db-dir-value}])

(def ^:private concurrency-flags
  [{:flag "--concurrency" :key :concurrency :parse :range-int :min 1 :max 20
    :error-invalid :invalid-concurrency :error-missing :missing-concurrency-value}
   {:flag "--min-delay" :key :min-delay :parse :non-neg-int
    :error-invalid :invalid-min-delay :error-missing :missing-min-delay-value}])

(def ^:private budget-flags
  [{:flag "--max-questions" :key :max-questions :parse :pos-int
    :error-invalid :invalid-max-questions :error-missing :missing-max-questions-value}
   {:flag "--stop-after" :key :stop-after :parse :pos-int
    :error-invalid :invalid-stop-after :error-missing :missing-stop-after-value}])

(def ^:private resume-flag
  {:flag "--resume" :key :resume :parse :optional-string})

(defn- with-provider-valid
  [specs valid-set]
  (mapv #(if (= "--provider" (:flag %)) (assoc % :valid valid-set) %) specs))

;; --- Declarative command specs ---

(def ^:private simple-command-spec
  {:flags (with-provider-valid common-flags benchmark-valid-providers)
   :initial {}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private query-command-spec
  {:flags (with-provider-valid common-flags benchmark-valid-providers)
   :initial {:subcommand "query"}
   :positionals {:required 2 :error :query-missing-args :keys [:query-name :repo-path]}})

(def ^:private benchmark-command-spec
  {:flags (vec (concat (with-provider-valid common-flags benchmark-valid-providers)
                       concurrency-flags
                       budget-flags
                       [resume-flag
                        {:flag "--judge-model" :key :judge-model :parse :string
                         :error-missing :missing-judge-model-value}
                        {:flag "--skip-raw" :key :skip-raw :parse :bool}
                        {:flag "--skip-judge" :key :skip-judge :parse :bool}
                        {:flag "--fast" :key :fast :parse :bool}
                        {:flag "--canary" :key :canary :parse :bool}]))
   :initial {:subcommand "benchmark"}
   :positionals {:required 1 :error :no-repo-path :keys [:repo-path]}})

(def ^:private longbench-run-command-spec
  {:flags (vec (concat [{:flag "--model" :key :model :parse :string
                         :error-missing :missing-model-value}
                        {:flag "--provider" :key :provider :parse :string
                         :valid longbench-valid-providers
                         :error-invalid :invalid-provider
                         :error-missing :missing-provider-value}]
                       concurrency-flags
                       budget-flags
                       [resume-flag]))
   :initial {:subcommand "longbench" :longbench-command "run"}})

(def ^:private longbench-results-command-spec
  {:flags [{:flag "--detail" :key :detail :parse :bool}]
   :initial {:subcommand "longbench" :longbench-command "results"}
   :positionals {:required 0 :error nil :keys [:run-id]}})

(def ^:private agent-command-spec
  {:flags [{:flag "--model" :key :model :parse :string
            :error-missing :missing-model-value}
           {:flag "--provider" :key :provider :parse :string
            :valid agent-valid-providers
            :error-invalid :invalid-provider
            :error-missing :missing-provider-value}
           {:flag "--max-iterations" :key :max-iterations :parse :pos-int
            :error-invalid :invalid-max-iterations
            :error-missing :missing-max-iterations-value}
           {:flag "--max-cost" :key :max-cost :parse :pos-double
            :error-invalid :invalid-max-cost
            :error-missing :missing-max-cost-value}
           {:flag "--db-dir" :key :db-dir :parse :string
            :error-missing :missing-db-dir-value}
           {:flag "-v" :key :verbose :parse :bool}]
   :initial {:subcommand "agent"}
   :positionals {:required 2 :error :agent-missing-args :keys [:question :repo-path]}})

;; --- Core parser ---

(defn- flag-map [flags]
  (into {} (map (juxt :flag identity)) flags))

(defn- parse-flags
  "Parse all flags in args. Returns [opts positional] or {:error ...}."
  [flags args initial]
  (let [lookup (flag-map flags)]
    (loop [remaining args
           opts initial
           positional []]
      (if (empty? remaining)
        [opts positional]
        (let [[arg & more] remaining]
          (if-let [spec (lookup arg)]
            (case (:parse spec)
              :bool
              (recur more (assoc opts (:key spec) true) positional)

              :optional-string
              (let [next-arg (first more)]
                (if (or (nil? next-arg) (str/starts-with? next-arg "-"))
                  (recur more (assoc opts (:key spec) "latest") positional)
                  (recur (rest more) (assoc opts (:key spec) next-arg) positional)))

              (let [raw (first more)]
                (if-not raw
                  {:error (:error-missing spec)}
                  (let [{:keys [ok error value]} (parse-value spec raw)]
                    (if error
                      {:error error :value value}
                      (if (and (:valid spec) (not ((:valid spec) raw)))
                        {:error (:error-invalid spec) :value raw}
                        (recur (rest more) (assoc opts (:key spec) ok) positional)))))))
            (if (str/starts-with? arg "-")
              {:error :unknown-flag :flag arg}
              (recur more opts (conj positional arg)))))))))

(defn- apply-positionals
  "Apply positional args by command spec. Returns opts or {:error ...}."
  [opts positional {:keys [required keys error]}]
  (if (or (nil? required) (>= (count positional) required))
    (reduce (fn [acc [k idx]]
              (if-let [v (nth positional idx nil)]
                (assoc acc k v)
                acc))
            opts
            (map vector keys (range)))
    {:error error}))

(defn- normalize-provider-opt
  [opts]
  (if-let [provider (:provider opts)]
    (assoc opts :provider (llm/normalize-provider-name provider))
    opts))

(defn- parse-command
  [command-spec args]
  (let [result (parse-flags (:flags command-spec) args (:initial command-spec))]
    (if (:error result)
      result
      (let [[opts positional] result
            opts* (if (:positionals command-spec)
                    (apply-positionals opts positional (:positionals command-spec))
                    opts)]
        (if (:error opts*)
          opts*
          (normalize-provider-opt opts*))))))

;; --- Subcommand parsers ---

(defn parse-benchmark-args [args]
  (let [result (parse-command benchmark-command-spec args)]
    (if (or (:error result) (not (:fast result)))
      result
      (-> result
          (assoc :skip-raw true :skip-judge true)
          (dissoc :fast)))))

(defn parse-longbench-run-args [args]
  (parse-command longbench-run-command-spec args))

(defn parse-longbench-results-args [args]
  (parse-command longbench-results-command-spec args))

(defn parse-longbench-args [args]
  (if (empty? args)
    {:error :longbench-no-subcommand :subcommand "longbench"}
    (let [[sub & rest-args] args]
      (case sub
        "download" {:subcommand "longbench" :longbench-command "download"}
        "run"      (parse-longbench-run-args rest-args)
        "results"  (parse-longbench-results-args rest-args)
        {:error :longbench-unknown-subcommand
         :subcommand "longbench"
         :longbench-command sub}))))

(defn parse-agent-args [args]
  (parse-command agent-command-spec args))

(defn parse-simple-args
  "Parse args for import/status/analyze/query subcommands."
  [sub args]
  (if (= "query" sub)
    (parse-command query-command-spec args)
    (let [result (parse-command simple-command-spec args)]
      (if (:error result)
        (assoc result :subcommand sub)
        (assoc result :subcommand sub)))))

(defn parse-args
  "Top-level CLI arg parser. Returns opts map or {:error keyword ...}."
  [args]
  (if (empty? args)
    {:error :no-args}
    (let [[sub & rest-args] args]
      (case sub
        "benchmark" (parse-benchmark-args rest-args)
        "longbench" (parse-longbench-args rest-args)
        "agent"     (parse-agent-args rest-args)
        (if (#{"import" "status" "analyze" "query"} sub)
          (parse-simple-args sub rest-args)
          {:error :unknown-subcommand :subcommand sub})))))
