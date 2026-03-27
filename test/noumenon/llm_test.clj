(ns noumenon.llm-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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
    (let [mock-request (fn [_opts]
                         (delay {:status 429 :body "rate limited" :error nil}))]
      (with-redefs [org.httpkit.client/request mock-request]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"API error: HTTP 429"
             (llm/invoke-api [{:role "user" :content "test"}]
                             {:model "m" :temperature 0.1
                              :max-tokens 128 :base-url "https://x"
                              :auth-token "t"})))))))

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

;; --- Provider resolution ---

(deftest provider->kw-known-providers
  (testing "known provider strings resolve to keywords"
    (is (= :glm (llm/provider->kw "glm")))
    (is (= :claude-api (llm/provider->kw "claude-api")))
    (is (= :claude-cli (llm/provider->kw "claude-cli")))
    (is (= :claude-cli (llm/provider->kw "claude")))))

(deftest provider->kw-keywords-work
  (testing "keyword inputs resolve correctly"
    (is (= :glm (llm/provider->kw :glm)))))

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

(deftest make-invoke-fn-glm-requires-token
  (testing "GLM provider throws when NOUMENON_ZAI_TOKEN is not set"
    ;; This test only runs when the env var is not set
    (when-not (System/getenv "NOUMENON_ZAI_TOKEN")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"NOUMENON_ZAI_TOKEN"
           (llm/make-invoke-fn :glm {:model "m" :temperature 0.1 :max-tokens 128}))))))

(deftest make-invoke-fn-claude-api-requires-key
  (testing "claude-api provider throws when ANTHROPIC_API_KEY is not set"
    (when-not (System/getenv "ANTHROPIC_API_KEY")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"ANTHROPIC_API_KEY"
           (llm/make-invoke-fn :claude-api {:model "m" :temperature 0.1 :max-tokens 128}))))))

(deftest make-invoke-fn-claude-cli-returns-fn
  (testing "claude-cli provider returns a function"
    (let [f (llm/make-invoke-fn :claude-cli {:model "haiku" :temperature 0.1 :max-tokens 128})]
      (is (fn? f)))))

;; --- flatten-messages ---

(deftest flatten-messages-single-user-message
  (is (= "User:\nhello"
         (llm/flatten-messages [{:role "user" :content "hello"}]))))

(deftest flatten-messages-multi-turn
  (is (= "User:\nhi\n\nAssistant:\nok\n\nUser:\nthanks"
         (llm/flatten-messages [{:role "user" :content "hi"}
                                {:role "assistant" :content "ok"}
                                {:role "user" :content "thanks"}]))))

(deftest flatten-messages-system-role
  (testing "system role uses role name verbatim"
    (is (= "system:\nbe helpful\n\nUser:\nhi"
           (llm/flatten-messages [{:role "system" :content "be helpful"}
                                  {:role "user" :content "hi"}])))))

(deftest flatten-messages-truncates-oversized-history
  (testing "drops oldest middle messages when total exceeds max-prompt-chars"
    (let [big-content (apply str (repeat 600000 "x"))
          messages [{:role "user" :content "system prompt"}
                    {:role "assistant" :content big-content}
                    {:role "user" :content "middle"}
                    {:role "assistant" :content "ok"}
                    {:role "user" :content "latest"}]
          result (llm/flatten-messages messages)]
      (is (<= (count result) 1000000))
      (is (str/includes? result "system prompt"))
      (is (str/includes? result "latest")))))
