(ns noumenon.llm
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]))

;; --- Usage tracking ---

(def zero-usage
  "Default usage map with all fields zeroed."
  {:input-tokens 0 :output-tokens 0 :cost-usd 0.0 :duration-ms 0})

(defn sum-usage
  "Sum two usage maps."
  [a b]
  {:input-tokens  (+ (:input-tokens a 0) (:input-tokens b 0))
   :output-tokens (+ (:output-tokens a 0) (:output-tokens b 0))
   :cost-usd      (+ (:cost-usd a 0.0) (:cost-usd b 0.0))
   :duration-ms   (+ (:duration-ms a 0) (:duration-ms b 0))})

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

;; --- Claude CLI invocation ---

(defn invoke-claude-cli
  "Invoke Claude Code CLI with the given prompt. Returns {:text string :usage map}.
   Accepts optional opts map with :model (string) and :env (map of env vars).
   Falls back to raw text + zero usage if JSON parse fails."
  ([prompt] (invoke-claude-cli prompt {}))
  ([prompt opts]
   (let [start-ms (System/currentTimeMillis)
         cmd      (cond-> ["claude" "--print" "--output-format" "json"]
                    (:model opts) (into ["--model" (:model opts)])
                    true          (into ["-p" prompt]))
         pb       (ProcessBuilder. ^java.util.List (vec cmd))
         _        (when-let [extra (:env opts)]
                    (let [env (.environment pb)]
                      (doseq [[k v] extra]
                        (.put env (str k) (str v)))))
         proc     (.start pb)
         out-f    (future (slurp (.getInputStream proc)))
         err-f    (future (slurp (.getErrorStream proc)))
         exit     (.waitFor proc)
         out-str  @out-f
         err-str  @err-f
         dur-ms   (- (System/currentTimeMillis) start-ms)]
     (when (not= 0 exit)
       (throw (ex-info (str "Claude CLI failed: " (str/trim (or err-str "")))
                       {:exit exit})))
     (let [parsed (try (json/read-str out-str :key-fn keyword)
                       (catch Exception _ nil))]
       (if (and (map? parsed) (contains? parsed :result))
         {:text           (:result parsed)
          :usage          (merge zero-usage
                                 (when-let [u (:usage parsed)]
                                   (cond-> {}
                                     (:input_tokens u)  (assoc :input-tokens (:input_tokens u))
                                     (:output_tokens u) (assoc :output-tokens (:output_tokens u))))
                                 (when-let [c (:total_cost_usd parsed)]
                                   {:cost-usd c})
                                 {:duration-ms dur-ms})
          :resolved-model (:model parsed)}
         (do (binding [*out* *err*]
               (println "Warning: Claude CLI returned non-JSON output, using raw text with zero usage"))
             {:text  out-str
              :usage (assoc zero-usage :duration-ms dur-ms)}))))))

;; --- CLI message flattening ---

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
  (invoke-claude-cli (flatten-messages messages) opts))

;; --- Model aliases ---

(defn model-alias->id
  "Map model alias to full model ID for Anthropic API."
  [alias]
  (case alias
    "sonnet"  "claude-sonnet-4-6-20250514"
    "haiku"   "claude-haiku-4-5-20251001"
    "opus"    "claude-opus-4-6-20250514"
    alias))

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

(defn make-prompt-fn
  "Wrap a messages-based invoke fn into a string-prompt fn.
   Returns (fn [prompt-string] -> {:text :usage :resolved-model})."
  [invoke-fn]
  (fn [prompt]
    (invoke-fn [{:role "user" :content prompt}])))
