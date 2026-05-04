(ns noumenon.llm-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [noumenon.llm :as llm]
            [org.httpkit.client]))

;; --- Tier 0: invoke-api with mock HTTP ---

(deftest invoke-api-success
  (testing "successful API call returns parsed response"
    (let [response-body (json/write-str
                         {:content [{:type "text" :text "The correct answer is (B)"}]
                          :usage {:input_tokens 500 :output_tokens 20}
                          :model "claude-3-5-sonnet-20241022"})
          mock-request  (fn [opts]
                          (is (= :post (:method opts)))
                          (is (re-find #"/v1/messages" (:url opts)))
                          (let [body (json/read-str (:body opts) :key-fn keyword)]
                            (is (= 0.1 (:temperature body)))
                            (is (= 128 (:max_tokens body)))
                            (is (= [{:role "user" :content "test prompt"}]
                                   (:messages body))))
                          (delay {:status 200 :body response-body :error nil}))]
      (with-redefs [org.httpkit.client/request mock-request]
        (let [result (llm/invoke-api [{:role "user" :content "test prompt"}]
                                     {:model "claude-3-5-sonnet-20241022"
                                      :temperature 0.1
                                      :max-tokens 128
                                      :base-url "https://api.example.com"
                                      :auth-token "test-token"})]
          (is (= "The correct answer is (B)" (:text result)))
          (is (= 500 (get-in result [:usage :input-tokens])))
          (is (= 20 (get-in result [:usage :output-tokens]))))))))

(deftest invoke-api-defaults-max-tokens
  (testing "max_tokens defaults to 4096 when not specified"
    (let [response-body (json/write-str
                         {:content [{:type "text" :text "ok"}]
                          :usage {:input_tokens 10 :output_tokens 5}
                          :model "m"})
          captured-body (atom nil)
          mock-request  (fn [opts]
                          (reset! captured-body (json/read-str (:body opts) :key-fn keyword))
                          (delay {:status 200 :body response-body :error nil}))]
      (with-redefs [org.httpkit.client/request mock-request]
        (llm/invoke-api [{:role "user" :content "test"}]
                        {:model "m" :temperature 0.1
                         :base-url "https://x" :auth-token "t"})
        (is (= 4096 (:max_tokens @captured-body)))))))

(deftest invoke-api-429-throws
  (testing "HTTP 429 throws ex-info with status code"
    (binding [llm/*max-retries* 1]
      (let [mock-request (fn [_opts]
                           (delay {:status 429 :body "rate limited" :error nil}))]
        (with-redefs [org.httpkit.client/request mock-request]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"API error: HTTP 429"
               (llm/invoke-api [{:role "user" :content "test"}]
                               {:model "m" :temperature 0.1
                                :max-tokens 128 :base-url "https://x"
                                :auth-token "t"}))))))))

(deftest invoke-api-500-throws
  (testing "HTTP 500 throws ex-info with status code after retries exhausted"
    (binding [llm/*max-retries* 1]
      (let [mock-request (fn [_opts]
                           (delay {:status 500 :body "server error" :error nil}))]
        (with-redefs [org.httpkit.client/request mock-request]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"API error: HTTP 500"
               (llm/invoke-api [{:role "user" :content "test"}]
                               {:model "m" :temperature 0.1
                                :max-tokens 128 :base-url "https://x"
                                :auth-token "t"}))))))))

(deftest invoke-api-error-omits-body-from-ex-data
  (testing "error ex-data contains status but not response body"
    (binding [llm/*max-retries* 1]
      (let [mock-request (fn [_opts]
                           (delay {:status 500 :body "sensitive error details" :error nil}))]
        (with-redefs [org.httpkit.client/request mock-request]
          (try
            (llm/invoke-api [{:role "user" :content "test"}]
                            {:model "m" :temperature 0.1
                             :max-tokens 128 :base-url "https://x"
                             :auth-token "t"})
            (catch clojure.lang.ExceptionInfo e
              (is (= 500 (:status (ex-data e))))
              (is (nil? (:body (ex-data e)))))))))))

(deftest invoke-api-connection-error-throws
  (testing "connection error throws ex-info after retries exhausted"
    (binding [llm/*max-retries* 1]
      (let [mock-request (fn [_opts]
                           (delay {:status nil :body nil
                                   :error (Exception. "connection refused")}))]
        (with-redefs [org.httpkit.client/request mock-request]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"API request failed: connection refused"
               (llm/invoke-api [{:role "user" :content "test"}]
                               {:model "m" :temperature 0.1
                                :max-tokens 128 :base-url "https://x"
                                :auth-token "t"}))))))))

;; --- Usage tracking ---

(deftest sum-usage-nil-handling
  (testing "nil arguments treated as zero-usage"
    (is (= llm/zero-usage (llm/sum-usage nil nil)))
    (is (= {:input-tokens 10 :output-tokens 5 :cost-usd 0.1 :duration-ms 100}
           (llm/sum-usage {:input-tokens 10 :output-tokens 5 :cost-usd 0.1 :duration-ms 100} nil)))
    (is (= {:input-tokens 10 :output-tokens 5 :cost-usd 0.1 :duration-ms 100}
           (llm/sum-usage nil {:input-tokens 10 :output-tokens 5 :cost-usd 0.1 :duration-ms 100})))))

(deftest sum-usage-normal
  (testing "sums two usage maps"
    (let [result (llm/sum-usage {:input-tokens 10 :output-tokens 5 :cost-usd 0.1 :duration-ms 100}
                                {:input-tokens 20 :output-tokens 10 :cost-usd 0.2 :duration-ms 200})]
      (is (= 30 (:input-tokens result)))
      (is (= 15 (:output-tokens result)))
      (is (= 300 (:duration-ms result)))
      (is (< (abs (- 0.3 (:cost-usd result))) 1e-10)))))

;; --- Provider resolution ---

(deftest provider->kw-known-providers
  (testing "known provider strings resolve to keywords"
    (is (= :glm (llm/provider->kw "glm")))
    (is (= :claude-api (llm/provider->kw "claude-api")))
    (is (= :claude-api (llm/provider->kw "claude")))))

(deftest provider->kw-keywords-work
  (testing "keyword inputs resolve correctly"
    (is (= :glm (llm/provider->kw :glm)))))

(deftest default-provider-name-from-edn-and-env
  (testing "NOUMENON_DEFAULT_PROVIDER overrides map default"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:default-provider :glm :gateway {:api-key \"k\"}}"
                                 "NOUMENON_DEFAULT_PROVIDER" "gateway"
                                 nil))]
      (is (= "gateway" (llm/default-provider-name))))))

(deftest provider-catalog-reports-defaults-and-models
  (with-redefs [llm/getenv (fn [k]
                             (case k
                               "NOUMENON_LLM_PROVIDERS_EDN" "{:default-provider :gateway :gateway {:api-key \"k\" :models [\"m1\" \"m2\"] :default-model \"m2\"}}"
                               nil))]
    (let [catalog (llm/provider-catalog)]
      (is (= "gateway" (:default-provider catalog)))
      (is (= ["m1" "m2"] (get-in catalog [:providers "gateway" :models])))
      (is (= "m2" (get-in catalog [:providers "gateway" :default-model]))))))

(deftest discover-provider-models-prefers-api
  (with-redefs [llm/getenv (fn [k]
                             (case k
                               "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"fallback\"]}}"
                               nil))
                org.httpkit.client/request (fn [_]
                                             (delay {:status 200
                                                     :body (json/write-str {:data [{:id "m-api-1"} {:id "m-api-2"}]})
                                                     :error nil}))]
    (let [r (llm/discover-provider-models "glm")]
      (is (= :api (:source r)))
      (is (= ["m-api-1" "m-api-2"] (:models r)))
      (is (nil? (:default-model r))))))

(deftest discover-provider-models-falls-back-on-api-failure
  (with-redefs [llm/getenv (fn [k]
                             (case k
                               "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"fallback\"] :default-model \"fallback\"}}"
                               nil))
                org.httpkit.client/request (fn [_]
                                             (delay {:status 500 :body "x" :error nil}))]
    (let [r (llm/discover-provider-models "glm")]
      (is (= :config (:source r)))
      (is (= ["fallback"] (:models r)))
      (is (= "fallback" (:default-model r))))))

(deftest provider->kw-rejects-unknown
  (testing "unrecognized provider throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unrecognized provider"
         (llm/provider->kw "openai")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unrecognized provider"
         (llm/provider->kw "badprovider")))))

(deftest provider->kw-claude-cli-migration-error
  (testing "removed claude-cli provider returns migration guidance"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"claude-cli has been removed"
         (llm/provider->kw "claude-cli")))))

;; --- Model aliases ---

(deftest model-alias->id-known-aliases
  (testing "known aliases resolve to full model IDs"
    (is (= "claude-sonnet-4-6-20250514" (llm/model-alias->id "sonnet")))
    (is (= "claude-haiku-4-5-20251001" (llm/model-alias->id "haiku")))
    (is (= "claude-opus-4-6-20250514" (llm/model-alias->id "opus")))))

(deftest model-alias->id-full-ids-pass-through
  (testing "full model IDs pass through unchanged"
    (is (= "claude-sonnet-4-6-20250514" (llm/model-alias->id "claude-sonnet-4-6-20250514")))))

(deftest model-alias->id-rejects-unknown
  (testing "unrecognized model strings throw"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unrecognized model"
         (llm/model-alias->id "gpt-4")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unrecognized model"
         (llm/model-alias->id "")))))

;; --- Provider factory ---

(deftest resolve-provider-config-prefers-edn-over-legacy-env
  (testing "NOUMENON_LLM_PROVIDERS_EDN entry overrides legacy env key"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:base-url \"https://gateway.example\" :api-key \"edn-key\"}}"
                                 "NOUMENON_ZAI_TOKEN" "legacy-key"
                                 nil))]
      (is (= {:base-url "https://gateway.example" :api-key "edn-key"}
             (llm/resolve-provider-config :glm))))))

(deftest resolve-provider-config-falls-back-to-legacy-env
  (testing "legacy env key remains compatible when provider EDN is unset"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "ANTHROPIC_API_KEY" "legacy-key"
                                 nil))]
      (is (= {:base-url "https://api.anthropic.com" :api-key "legacy-key"}
             (llm/resolve-provider-config :claude-api))))))

(deftest resolve-provider-config-validates-url
  (testing "invalid base URL fails clearly"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:base-url \"not-a-url\" :api-key \"k\"}}"
                                 nil))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid base URL"
           (llm/resolve-provider-config :glm)))))
  (testing "service mode requires https"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_RUNTIME_MODE" "service"
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:base-url \"http://gateway.example\" :api-key \"k\"}}"
                                 nil))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"requires https"
           (llm/resolve-provider-config :glm))))))

(deftest resolve-provider-config-missing-key-error-does-not-leak-secret
  (testing "missing-key error message does not include secret value"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_RUNTIME_MODE" "service"
                                 nil))]
      (try
        (llm/resolve-provider-config :glm)
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"Missing API key" (.getMessage e)))
          (is (not (re-find #"token=|api-key=|legacy-key|file-key|edn-key" (.getMessage e)))))))))

(deftest resolve-opts-uses-provider-default-model
  (testing "provider :default-model is used when --model is not provided"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :default-model \"my-gateway-model\"}}"
                                 nil))]
      (is (= "my-gateway-model" (:model-id (llm/resolve-opts {:provider "glm"})))))))

(deftest resolve-opts-validates-model-against-provider-models
  (testing "selected model must be in provider :models when configured"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"m1\" \"m2\"] :default-model \"m1\"}}"
                                 nil))]
      (is (= "m1" (:model-id (llm/resolve-opts {:provider "glm"}))))
      (is (= "m2" (:model-id (llm/resolve-opts {:provider "glm" :model "m2"}))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not configured for provider"
           (llm/resolve-opts {:provider "glm" :model "m3"}))))))

(deftest resolve-opts-validates-default-model-membership
  (testing ":default-model must be listed in :models when :models exists"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"m1\"] :default-model \"m2\"}}"
                                 nil))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"default-model is not listed"
           (llm/resolve-opts {:provider "glm"}))))))

(deftest resolve-opts-requires-explicit-model-selection
  (testing "fails when provider has neither --model nor :default-model"
    (with-redefs [llm/getenv (fn [k]
                               (case k
                                 "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"m1\"]}}"
                                 nil))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No model selected"
           (llm/resolve-opts {:provider "glm"}))))))

(deftest resolve-opts-config-errors-carry-http-status-400
  (testing "Configuration-class throws (no model selected, default-model
            not listed, model not configured) carry `:status 400` so HTTP
            handlers render them as 400 with the actionable message
            instead of swallowing them as bare 500 'Internal server
            error'. The same throws are also user-actionable — the
            client can pass `model` in the request body to override —
            so 400 is the right code."
    (testing "missing model selection carries :status 400"
      (with-redefs [llm/getenv (fn [k]
                                 (case k
                                   "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"m1\"]}}"
                                   nil))]
        (let [err (try (llm/resolve-opts {:provider "glm"})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? err))
          (is (= 400 (:status (ex-data err)))
              (str "expected :status 400, got: " (pr-str (ex-data err))))
          (is (re-find #"No model selected" (.getMessage err))))))
    (testing "default-model not in :models carries :status 400"
      (with-redefs [llm/getenv (fn [k]
                                 (case k
                                   "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"m1\"] :default-model \"m2\"}}"
                                   nil))]
        (let [err (try (llm/resolve-opts {:provider "glm"})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? err))
          (is (= 400 (:status (ex-data err)))))))
    (testing "explicit model not in configured :models carries :status 400"
      (with-redefs [llm/getenv (fn [k]
                                 (case k
                                   "NOUMENON_LLM_PROVIDERS_EDN" "{:glm {:api-key \"k\" :models [\"m1\"] :default-model \"m1\"}}"
                                   nil))]
        (let [err (try (llm/resolve-opts {:provider "glm" :model "not-listed"})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
          (is (some? err))
          (is (= 400 (:status (ex-data err)))))))))
