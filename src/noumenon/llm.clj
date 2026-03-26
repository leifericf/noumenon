(ns noumenon.llm
  (:require [clojure.data.json :as json]
            [noumenon.analyze :as analyze]
            [org.httpkit.client :as http]))

;; --- Direct API invocation ---

(defn invoke-api
  "Invoke Anthropic Messages API directly via http-kit.
   Returns {:text string :usage {:input-tokens n :output-tokens m} :model string}.
   Throws ex-info on HTTP errors (429, 500, timeout) with status and resume guidance."
  [prompt {:keys [model temperature max-tokens base-url auth-token]}]
  (let [url      (str base-url "/v1/messages")
        start-ms (System/currentTimeMillis)
        body     (json/write-str
                  {:model      model
                   :max_tokens max-tokens
                   :temperature temperature
                   :messages   [{:role "user" :content prompt}]})
        {:keys [status body error]}
        @(http/request {:url     url
                        :method  :post
                        :headers {"Content-Type"      "application/json"
                                  "x-api-key"         auth-token
                                  "anthropic-version"  "2023-06-01"}
                        :body    body
                        :timeout 300000})
        dur-ms   (- (System/currentTimeMillis) start-ms)]
    (when error
      (throw (ex-info (str "API request failed: " (.getMessage ^Exception error)
                           ". Resume: clj -M:run longbench run --resume")
                      {:error error})))
    (when (not= 200 status)
      (throw (ex-info (str "API error: HTTP " status
                           ". Resume: clj -M:run longbench run --resume")
                      {:status status :body body})))
    (let [parsed (json/read-str body :key-fn keyword)
          text   (->> (:content parsed)
                      (filter #(= "text" (:type %)))
                      first
                      :text)
          usage  (:usage parsed)]
      {:text  text
       :usage {:input-tokens  (:input_tokens usage 0)
               :output-tokens (:output_tokens usage 0)
               :cost-usd      0.0
               :duration-ms   dur-ms}
       :model (:model parsed)})))

;; --- Claude CLI wrapper ---

(defn invoke-cli
  "Invoke Claude via CLI. Wraps analyze/invoke-claude-cli."
  [prompt opts]
  (analyze/invoke-claude-cli prompt opts))

;; --- Provider factory ---

(defn make-invoke-fn
  "Create an invoke function for the given provider.
   Returns (fn [prompt] -> {:text :usage :model}).
   Provider :glm — direct API via Z.ai proxy, reads NOUMENON_ZAI_TOKEN.
   Provider :claude-api — direct API to Anthropic, reads ANTHROPIC_API_KEY.
   Provider :claude-cli — fallback via `claude --print`."
  [provider {:keys [model temperature max-tokens]}]
  (case provider
    :glm
    (let [token (System/getenv "NOUMENON_ZAI_TOKEN")]
      (when-not token
        (throw (ex-info "NOUMENON_ZAI_TOKEN environment variable is not set"
                        {:provider :glm})))
      (fn [prompt]
        (invoke-api prompt {:model       model
                            :temperature temperature
                            :max-tokens  max-tokens
                            :base-url    "https://api.z.ai/api/anthropic"
                            :auth-token  token})))

    :claude-api
    (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
      (when-not api-key
        (throw (ex-info "ANTHROPIC_API_KEY environment variable is not set. Check .env setup."
                        {:provider :claude-api})))
      (fn [prompt]
        (invoke-api prompt {:model       model
                            :temperature temperature
                            :max-tokens  max-tokens
                            :base-url    "https://api.anthropic.com"
                            :auth-token  api-key})))

    :claude-cli
    (fn [prompt]
      (invoke-cli prompt (when model {:model model})))))
