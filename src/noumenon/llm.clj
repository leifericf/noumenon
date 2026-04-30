(ns noumenon.llm
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [noumenon.util :refer [log! truncate]]
            [org.httpkit.client :as http])
  (:import [java.net URI]))

;; --- Provider/model defaults ---

(def default-provider "glm")
(def provider-aliases
  {"claude" "claude-api"})

(def canonical-providers
  #{"glm" "claude-api"})

(defn- getenv
  [k]
  (System/getenv k))

(defn- parse-provider-map
  []
  (if-let [raw (getenv "NOUMENON_LLM_PROVIDERS_EDN")]
    (let [parsed (try
                   (edn/read-string raw)
                   (catch Exception e
                     (throw (ex-info "NOUMENON_LLM_PROVIDERS_EDN contains invalid EDN"
                                     {:env-var "NOUMENON_LLM_PROVIDERS_EDN"}
                                     e))))]
      (when-not (map? parsed)
        (throw (ex-info "NOUMENON_LLM_PROVIDERS_EDN must be an EDN map"
                        {:env-var "NOUMENON_LLM_PROVIDERS_EDN"})))
      parsed)
    {}))

(defn- configured-provider-names
  []
  (->> (parse-provider-map)
       keys
       (filter keyword?)
       (remove #{:default-provider})
       (map name)
       set))

(defn supported-provider-names
  []
  (sort (into canonical-providers (configured-provider-names))))

(defn default-provider-name
  []
  (let [providers-edn (parse-provider-map)
        env-default   (getenv "NOUMENON_DEFAULT_PROVIDER")
        map-default   (some-> (:default-provider providers-edn) name)
        selected      (or env-default map-default default-provider)]
    (if ((set (supported-provider-names)) selected)
      selected
      (throw (ex-info (str "Default provider is not supported: " selected)
                      {:provider selected :known (supported-provider-names)})))))

(defn normalize-provider-name
  "Normalize provider name string to canonical form.
   Returns nil when the input is not a supported provider."
  [provider]
  (when provider
    (let [normalized (get provider-aliases provider provider)
          providers  (set (supported-provider-names))]
      (when (providers normalized)
        normalized))))

(defn provider->kw
  "Convert a provider string/keyword to canonical provider keyword.
   Throws on unrecognized provider strings."
  [provider]
  (let [provider-name (if (keyword? provider) (name provider) provider)
        normalized    (normalize-provider-name provider-name)]
    (when-not normalized
      (throw (ex-info (str (if (= "claude-cli" provider-name)
                             "Provider claude-cli has been removed. Use claude-api (or claude) and configure an API key."
                             (str "Unrecognized provider: " provider-name
                                  ". Known providers: " (str/join ", " (supported-provider-names)))))
                      {:provider provider-name :known (supported-provider-names)})))
    (keyword normalized)))

;; --- Pricing ---

(def model-pricing
  "Per-token pricing in $/1M tokens. Only for direct Anthropic API models.
   GLM uses quota-based pricing so is not listed here."
  {"claude-sonnet-4-6-20250514" {:input 3.0  :output 15.0}
   "claude-haiku-4-5-20251001"  {:input 0.80 :output 4.0}
   "claude-opus-4-6-20250514"   {:input 15.0 :output 75.0}})

(defn estimate-cost
  "Estimate USD cost for given model and token counts. Returns 0.0 for unknown models."
  [model-id input-tokens output-tokens]
  (if-let [{:keys [input output]} (model-pricing model-id)]
    (+ (* input-tokens (/ input 1e6))
       (* output-tokens (/ output 1e6)))
    0.0))

;; --- Usage tracking ---

(def zero-usage
  "Default usage map with all fields zeroed."
  {:input-tokens 0 :output-tokens 0 :cost-usd 0.0 :duration-ms 0})

(defn sum-usage
  "Sum two usage maps. Treats nil as zero-usage."
  [a b]
  (merge-with + (or a zero-usage) (or b zero-usage)))

;; --- Direct API invocation ---

(def ^:private retryable-status #{429 500 502 503 504})
(def ^:dynamic *max-retries* 3)
(def ^:dynamic *retry-delays-ms* [2000 4000])

(defn- build-api-request
  "Build the JSON request body and http-kit options for the Messages API."
  [messages {:keys [model temperature max-tokens base-url auth-token system]}]
  {:url     (str base-url "/v1/messages")
   :method  :post
   :headers {"Content-Type"     "application/json"
             "x-api-key"        auth-token
             "anthropic-version" "2023-06-01"}
   :body    (json/write-str
             (cond-> {:model model :max_tokens (or max-tokens 4096) :messages messages}
               temperature (assoc :temperature temperature)
               system      (assoc :system [{:type "text" :text system
                                            :cache_control {:type "ephemeral"}}])))
   :timeout 300000})

(defn- parse-api-response
  "Parse a successful API response body into {:text :usage :model :resolved-model}."
  [body start-ms]
  (let [dur-ms  (- (System/currentTimeMillis) start-ms)
        parsed  (json/read-str body :key-fn keyword)
        text    (some #(when (= "text" (:type %)) (:text %)) (:content parsed))
        usage   (:usage parsed)
        in      (:input_tokens usage 0)
        out     (:output_tokens usage 0)
        cached  (:cache_read_input_tokens usage 0)
        created (:cache_creation_input_tokens usage 0)]
    (when-not text
      (log! (str "WARNING: API returned HTTP 200 but no text content"
                 " (stop_reason=" (:stop_reason parsed)
                 " content=" (truncate (pr-str (:content parsed)) 200) ")")))
    {:text           text
     :usage          (cond-> {:input-tokens in :output-tokens out
                              :cost-usd (estimate-cost (:model parsed) in out)
                              :duration-ms dur-ms}
                       (pos? cached)  (assoc :cache-read-tokens cached)
                       (pos? created) (assoc :cache-creation-tokens created))
     :model          (:model parsed)
     :resolved-model (:model parsed)}))

(defn invoke-api
  "Invoke Anthropic Messages API directly via http-kit.
   `messages` is [{:role \"user\"/\"assistant\" :content string} ...].
   Returns {:text string :usage {:input-tokens n :output-tokens m} :model string}.
   Makes up to 3 attempts on transient errors (429, 5xx, connection failures).
   Throws ex-info on persistent HTTP errors."
  [messages opts]
  (let [req      (build-api-request messages opts)
        start-ms (System/currentTimeMillis)]
    (loop [attempt 1]
      (let [{:keys [status body error]} @(http/request req)
            retryable?    (or error (retryable-status status))
            last-attempt? (>= attempt *max-retries*)
            retry!        (fn [msg]
                            (log! (str "  Retry " attempt "/" *max-retries* ": " msg))
                            (Thread/sleep (get *retry-delays-ms* (dec attempt) 4000)))]
        (cond
          (and error retryable? (not last-attempt?))
          (do (retry! (.getMessage ^Exception error)) (recur (inc attempt)))

          error
          (throw (ex-info (str "API request failed: " (.getMessage ^Exception error))
                          {:error error :attempts attempt}))

          (and (not= 200 status) retryable? (not last-attempt?))
          (do (retry! (str "HTTP " status)) (recur (inc attempt)))

          (not= 200 status)
          (throw (ex-info (str "API error: HTTP " status)
                          {:status status :attempts attempt}))

          :else
          (parse-api-response body start-ms))))))

;; --- Model aliases ---

(def model-aliases
  "Map of short alias to full Anthropic model ID."
  {"sonnet" "claude-sonnet-4-6-20250514"
   "haiku"  "claude-haiku-4-5-20251001"
   "opus"   "claude-opus-4-6-20250514"})

(def known-model-ids
  "Set of all recognized model IDs (full IDs and aliases)."
  (into (set (keys model-aliases)) (vals model-aliases)))

(defn model-alias->id
  "Map model alias to full model ID for Anthropic API.
   Throws on unrecognized model strings."
  [alias]
  (when-not (known-model-ids alias)
    (throw (ex-info (str "Unrecognized model: " alias
                         ". Known models: " (str/join ", " (sort known-model-ids)))
                    {:model alias :known (sort known-model-ids)})))
  (get model-aliases alias alias))

;; --- Provider factory ---

(def ^:private api-provider-config
  {:glm       {:env-var  "NOUMENON_ZAI_TOKEN"
               :base-url "https://api.z.ai/api/anthropic"
               :models-path "/v1/models"}
   :claude-api {:env-var  "ANTHROPIC_API_KEY"
                :base-url "https://api.anthropic.com"
                :models-path "/v1/models"}})

(def ^:private runtime-modes #{"local" "service"})

(defn- runtime-mode
  []
  (let [mode (or (getenv "NOUMENON_RUNTIME_MODE") "local")]
    (when-not (runtime-modes mode)
      (throw (ex-info (str "Invalid NOUMENON_RUNTIME_MODE: " mode
                           ". Expected one of: " (str/join ", " (sort runtime-modes)))
                      {:runtime-mode mode :known (sort runtime-modes)})))
    mode))

(defn- service-mode?
  []
  (= "service" (runtime-mode)))

(defn- read-env-var
  [env-var]
  (getenv env-var))

(defn- parse-edn-env
  [env-var]
  (when-let [raw (getenv env-var)]
    (try
      (edn/read-string raw)
      (catch Exception e
        (throw (ex-info (str env-var " contains invalid EDN")
                        {:env-var env-var}
                        e))))))

(defn- provider-map-config
  [provider-kw]
  (get (parse-provider-map) provider-kw))

(defn provider-catalog
  []
  (let [names     (supported-provider-names)
        default-p (default-provider-name)]
    {:default-provider default-p
     :providers        (into {}
                             (map (fn [provider-name]
                                    (let [provider-kw  (keyword provider-name)
                                          configured   (provider-map-config provider-kw)
                                          configured-models (:models configured)
                                          default-model (:default-model configured)]
                                      [provider-name {:default?      (= provider-name default-p)
                                                      :default-model default-model
                                                      :models        (or configured-models [])}])))
                             names)}))

(defn- model-id-from-entry
  [entry]
  (or (:id entry) (:name entry) (:model entry)))

(defn- parse-models-response
  [body]
  (let [parsed (json/read-str body :key-fn keyword)
        data   (cond
                 (vector? parsed) parsed
                 (vector? (:data parsed)) (:data parsed)
                 :else [])]
    (->> data
         (map model-id-from-entry)
         (filter string?)
         vec)))

(defn- models-url
  [provider-kw base-url]
  (let [configured (provider-map-config provider-kw)
        path       (or (:models-path configured)
                       (:models-path (api-provider-config provider-kw)))]
    (when path
      (str (str/replace (str/trim base-url) #"/+$" "") path))))

(defn discover-provider-models
  ([provider] (discover-provider-models provider {}))
  ([provider {:keys [timeout-ms] :or {timeout-ms 15000}}]
   (let [provider-kw              (provider->kw provider)
         configured               (provider-map-config provider-kw)
         {:keys [env-var base-url]} (api-provider-config provider-kw)
         resolved-url             (or (:base-url configured) base-url)
         api-key                  (or (:api-key configured) (read-env-var env-var))
         url                      (models-url provider-kw resolved-url)
         fallback                 (vec (:models configured []))]
     (if-not url
       {:provider      (name provider-kw)
        :default-model (:default-model configured)
        :models        fallback
        :source        :config}
       (try
         (let [{:keys [status body error]} @(http/request {:url url
                                                           :method :get
                                                           :headers {"x-api-key" api-key
                                                                     "anthropic-version" "2023-06-01"}
                                                           :timeout timeout-ms})]
           (if (or error (not= 200 status))
             {:provider      (name provider-kw)
              :default-model (:default-model configured)
              :models        fallback
              :source        :config
              :warning       (str "Model discovery unavailable (HTTP " status "), using configured fallback")}
             (let [models (parse-models-response body)]
               {:provider      (name provider-kw)
                :default-model (:default-model configured)
                :models        (if (seq models) models fallback)
                :source        (if (seq models) :api :config)})))
         (catch Exception _
           {:provider      (name provider-kw)
            :default-model (:default-model configured)
            :models        fallback
            :source        :config
            :warning       "Model discovery failed, using configured fallback"}))))))

(defn- provider-default-model
  [provider-kw]
  (:default-model (provider-map-config provider-kw)))

(defn- provider-models
  [provider-kw]
  (:models (provider-map-config provider-kw)))

(defn- normalize-model-name
  [model-name]
  (if (known-model-ids model-name)
    (model-alias->id model-name)
    model-name))

(defn- configured-model-set
  [provider-kw]
  (some->> (provider-models provider-kw)
           (map normalize-model-name)
           set))

(defn- resolve-model-id
  [provider-kw model]
  (let [configured    (configured-model-set provider-kw)
        default-model (provider-default-model provider-kw)
        selected      (or model default-model)
        resolved   (normalize-model-name selected)]
    (when-not selected
      (throw (ex-info (str "No model selected for provider " (name provider-kw)
                           ". Set :default-model in NOUMENON_LLM_PROVIDERS_EDN or pass --model.")
                      {:provider provider-kw})))
    (when (and default-model (seq configured)
               (not (configured (normalize-model-name default-model))))
      (throw (ex-info (str "Configured :default-model is not listed in :models for provider " (name provider-kw))
                      {:provider provider-kw :default-model default-model :allowed (sort configured)})))
    (when (and (seq configured) (not (configured resolved)))
      (throw (ex-info (str "Model " resolved " is not configured for provider " (name provider-kw))
                      {:provider provider-kw :model resolved :allowed (sort configured)})))
    resolved))

(defn- normalize-base-url
  [base-url]
  (when base-url
    (str/replace (str/trim base-url) #"/+$" "")))

(defn- valid-absolute-url?
  [s]
  (try
    (let [uri (URI. s)]
      (and (.isAbsolute uri) (seq (.getHost uri))))
    (catch Exception _
      false)))

(defn- parse-allowlist
  []
  (let [allowlist (parse-edn-env "NOUMENON_LLM_BASE_URL_ALLOWLIST_EDN")]
    (when (and allowlist (not (sequential? allowlist)))
      (throw (ex-info "NOUMENON_LLM_BASE_URL_ALLOWLIST_EDN must be a sequential EDN value"
                      {:env-var "NOUMENON_LLM_BASE_URL_ALLOWLIST_EDN"})))
    (set (map str allowlist))))

(defn- allowlisted-host?
  [host allowlist]
  (or (empty? allowlist)
      (contains? allowlist host)
      (some #(and (str/starts-with? % "*.")
                  (str/ends-with? host (subs % 1)))
            allowlist)))

(defn- validate-base-url!
  [provider-kw base-url]
  (when-not (valid-absolute-url? base-url)
    (throw (ex-info (str "Invalid base URL for provider " (name provider-kw) ": must be absolute")
                    {:provider provider-kw})))
  (let [uri      (URI. base-url)
        scheme   (.getScheme uri)
        host     (.getHost uri)
        allowset (parse-allowlist)]
    (when (and (service-mode?) (not= "https" (str/lower-case scheme)))
      (throw (ex-info (str "Service mode requires https base URL for provider " (name provider-kw))
                      {:provider provider-kw})))
    (when-not (allowlisted-host? host allowset)
      (throw (ex-info (str "Base URL host is not allowlisted for provider " (name provider-kw))
                      {:provider provider-kw :host host}))))
  base-url)

(defn resolve-provider-config
  "Resolve provider configuration to {:base-url :api-key} with precedence:
   providers EDN map -> process env vars -> default base URL."
  [provider]
  (let [provider-kw (provider->kw provider)
        {:keys [env-var base-url]} (api-provider-config provider-kw)
        provider-edn  (provider-map-config provider-kw)
        resolved-url  (normalize-base-url (or (:base-url provider-edn) base-url))
        resolved-key  (or (:api-key provider-edn) (read-env-var env-var))]
    (when-not resolved-key
      (throw (ex-info (str "Missing API key for provider " (name provider-kw)
                           ". Set :api-key in NOUMENON_LLM_PROVIDERS_EDN or " env-var)
                      {:provider provider-kw})))
    {:base-url (validate-base-url! provider-kw resolved-url)
     :api-key  resolved-key}))

(defn make-messages-fn
  "Create an invoke function for the given provider.
   Returns (fn [messages & [opts]]) where messages is
   [{:role \"user\"/\"assistant\" :content string} ...].
   Optional opts map supports :system (string) for prompt caching.
   Provider :glm — direct API via Z.ai proxy, reads NOUMENON_ZAI_TOKEN.
   Provider :claude-api — direct API to Anthropic, reads ANTHROPIC_API_KEY."
  [provider {:keys [model temperature max-tokens]}]
  (let [kw (provider->kw provider)
        {:keys [base-url api-key]} (resolve-provider-config kw)]
    (fn invoke
      ([messages] (invoke messages nil))
      ([messages opts]
       (invoke-api messages (cond-> {:model       model
                                     :temperature temperature
                                     :max-tokens  max-tokens
                                     :base-url    base-url
                                     :auth-token  api-key}
                              (:system opts) (assoc :system (:system opts))))))))

(defn wrap-as-prompt-fn
  "Wrap a messages-based invoke fn into a string-prompt fn.
   Returns (fn [prompt-string] -> {:text :usage :resolved-model})."
  [invoke-fn]
  (fn [prompt]
    (invoke-fn [{:role "user" :content prompt}])))

(defn resolve-opts
  "Resolve provider/model from option defaults. Returns map with :provider-kw
    and :model-id — the canonical, validated identifiers."
  [{:keys [provider model]}]
  (let [provider-kw (provider->kw (or provider (default-provider-name)))]
    {:provider-kw provider-kw
     :model-id    (resolve-model-id provider-kw model)}))

(defn make-messages-fn-from-opts
  "Build a messages-based invoke-fn from provider/model options.
   Returns {:invoke-fn fn, :model-id string, :provider-kw keyword}."
  [{:keys [temperature max-tokens] :as opts}]
  (let [{:keys [provider-kw model-id]} (resolve-opts opts)]
    {:invoke-fn  (make-messages-fn provider-kw
                                   (cond-> {:model model-id}
                                     temperature (assoc :temperature temperature)
                                     max-tokens  (assoc :max-tokens max-tokens)))
     :model-id   model-id
     :provider-kw provider-kw}))

(defn wrap-as-prompt-fn-from-opts
  "Build a prompt-fn (string->result) from provider/model options.
   Returns {:prompt-fn fn, :model-id string, :provider-kw keyword}."
  [opts]
  (let [{:keys [invoke-fn] :as resolved} (make-messages-fn-from-opts opts)]
    (-> resolved
        (assoc :prompt-fn (wrap-as-prompt-fn invoke-fn))
        (dissoc :invoke-fn))))

(defn make-isolated-prompt-fn
  "Build an isolated prompt-fn for benchmark raw-mode calls."
  [opts]
  (:prompt-fn (wrap-as-prompt-fn-from-opts opts)))
