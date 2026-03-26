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
                            (is (= 128 (:max_tokens body))))
                          (delay {:status 200 :body response-body :error nil}))]
      (with-redefs [org.httpkit.client/request mock-request]
        (let [result (llm/invoke-api "test prompt"
                                     {:model "claude-3-5-sonnet-20241022"
                                      :temperature 0.1
                                      :max-tokens 128
                                      :base-url "https://api.example.com"
                                      :auth-token "test-token"})]
          (is (= "The correct answer is (B)" (:text result)))
          (is (= 500 (get-in result [:usage :input-tokens])))
          (is (= 20 (get-in result [:usage :output-tokens]))))))))

(deftest invoke-api-429-throws
  (testing "HTTP 429 throws ex-info with resume guidance"
    (let [mock-request (fn [_opts]
                         (delay {:status 429 :body "rate limited" :error nil}))]
      (with-redefs [org.httpkit.client/request mock-request]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"API error: HTTP 429.*Resume"
             (llm/invoke-api "test" {:model "m" :temperature 0.1
                                     :max-tokens 128 :base-url "https://x"
                                     :auth-token "t"})))))))

(deftest invoke-api-500-throws
  (testing "HTTP 500 throws ex-info with resume guidance"
    (let [mock-request (fn [_opts]
                         (delay {:status 500 :body "server error" :error nil}))]
      (with-redefs [org.httpkit.client/request mock-request]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"API error: HTTP 500.*Resume"
             (llm/invoke-api "test" {:model "m" :temperature 0.1
                                     :max-tokens 128 :base-url "https://x"
                                     :auth-token "t"})))))))

(deftest invoke-api-connection-error-throws
  (testing "connection error throws ex-info with resume guidance"
    (let [mock-request (fn [_opts]
                         (delay {:status nil :body nil
                                 :error (Exception. "connection refused")}))]
      (with-redefs [org.httpkit.client/request mock-request]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"API request failed.*Resume"
             (llm/invoke-api "test" {:model "m" :temperature 0.1
                                     :max-tokens 128 :base-url "https://x"
                                     :auth-token "t"})))))))

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
