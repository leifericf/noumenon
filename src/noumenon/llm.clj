(ns noumenon.llm
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [noumenon.analyze :as analyze]
            [org.httpkit.client :as http]))

;; --- Direct API invocation ---

(defn invoke-api
  "Invoke Anthropic Messages API directly via http-kit.
   `messages` is [{:role \"user\"/\"assistant\" :content string} ...].
   Returns {:text string :usage {:input-tokens n :output-tokens m} :model string}.
   Throws ex-info on HTTP errors (429, 500, timeout) with status and resume guidance."
  [messages {:keys [model temperature max-tokens base-url auth-token]}]
  (let [url      (str base-url "/v1/messages")
        start-ms (System/currentTimeMillis)
        body     (json/write-str
                  {:model      model
                   :max_tokens max-tokens
                   :temperature temperature
                   :messages   messages})
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

(defn flatten-messages
  "Flatten a messages vector into a single prompt string for CLI invocation.
   Prefixes each message with its role, separated by blank lines."
  [messages]
  (->> messages
       (map (fn [{:keys [role content]}]
              (str (case role "user" "User" "assistant" "Assistant" (str role)) ":\n" content)))
       (str/join "\n\n")))

(defn invoke-cli
  "Invoke Claude via CLI. Flattens messages to a single prompt string."
  [messages opts]
  (analyze/invoke-claude-cli (flatten-messages messages) opts))

;; --- Provider factory ---

(defn make-invoke-fn
  "Create an invoke function for the given provider.
   Returns (fn [messages] -> {:text :usage :model}) where messages is
   [{:role \"user\"/\"assistant\" :content string} ...].
   Opts (model, temperature, etc.) are baked in at factory time.
   Provider :glm — direct API via Z.ai proxy, reads NOUMENON_ZAI_TOKEN.
   Provider :claude-api — direct API to Anthropic, reads ANTHROPIC_API_KEY.
   Provider :claude-cli — flattens messages to single prompt string."
  [provider {:keys [model temperature max-tokens]}]
  (case provider
    :glm
    (let [token (System/getenv "NOUMENON_ZAI_TOKEN")]
      (when-not token
        (throw (ex-info "NOUMENON_ZAI_TOKEN environment variable is not set"
                        {:provider :glm})))
      (fn [messages]
        (invoke-api messages {:model       model
                              :temperature temperature
                              :max-tokens  max-tokens
                              :base-url    "https://api.z.ai/api/anthropic"
                              :auth-token  token})))

    :claude-api
    (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
      (when-not api-key
        (throw (ex-info "ANTHROPIC_API_KEY environment variable is not set. Check .env setup."
                        {:provider :claude-api})))
      (fn [messages]
        (invoke-api messages {:model       model
                              :temperature temperature
                              :max-tokens  max-tokens
                              :base-url    "https://api.anthropic.com"
                              :auth-token  api-key})))

    :claude-cli
    (fn [messages]
      (invoke-cli messages (when model {:model model})))))
