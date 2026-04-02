(ns noumenon.llm
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [noumenon.util :refer [log! truncate]]
            [org.httpkit.client :as http])
  (:import [java.util.concurrent TimeUnit]))

;; --- Provider/model defaults ---

(def default-provider "glm")
(def default-model-alias "sonnet")

(def provider-aliases
  {"claude" "claude-cli"})

(def canonical-providers
  #{"glm" "claude-api" "claude-cli"})

(defn normalize-provider-name
  "Normalize provider name string to canonical form.
   Returns nil when the input is not a supported provider."
  [provider]
  (when provider
    (let [normalized (get provider-aliases provider provider)]
      (when (canonical-providers normalized)
        normalized))))

(defn provider->kw
  "Convert a provider string/keyword to canonical provider keyword.
   Throws on unrecognized provider strings."
  [provider]
  (let [provider-name (if (keyword? provider) (name provider) provider)
        normalized    (normalize-provider-name provider-name)]
    (when-not normalized
      (throw (ex-info (str "Unrecognized provider: " provider-name
                           ". Known providers: " (str/join ", " (sort canonical-providers)))
                      {:provider provider-name :known (sort canonical-providers)})))
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

(defn invoke-api
  "Invoke Anthropic Messages API directly via http-kit.
   `messages` is [{:role \"user\"/\"assistant\" :content string} ...].
   Optional :system in opts is a string placed in the top-level system field
   with cache_control for prompt caching.
   Returns {:text string :usage {:input-tokens n :output-tokens m} :model string}.
   Makes up to 3 attempts (2 retries) on transient errors (429, 5xx, connection failures).
   Throws ex-info on persistent HTTP errors."
  [messages {:keys [model temperature max-tokens base-url auth-token system]}]
  (let [url      (str base-url "/v1/messages")
        req-body (json/write-str
                  (cond-> {:model      model
                           :max_tokens (or max-tokens 4096)
                           :messages   messages}
                    temperature (assoc :temperature temperature)
                    system      (assoc :system [{:type "text"
                                                 :text system
                                                 :cache_control {:type "ephemeral"}}])))
        start-ms (System/currentTimeMillis)]
    (loop [attempt 1]
      (let [{:keys [status body error]}
            @(http/request {:url     url
                            :method  :post
                            :headers {"Content-Type"      "application/json"
                                      "x-api-key"         auth-token
                                      "anthropic-version"  "2023-06-01"}
                            :body    req-body
                            :timeout 300000})
            retryable?    (or error (retryable-status status))
            last-attempt? (>= attempt *max-retries*)
            retry!        (fn [msg]
                            (log! (str "  Retry " attempt "/" *max-retries* ": " msg))
                            (Thread/sleep (get *retry-delays-ms* (dec attempt) 4000)))]
        (cond
          (and error retryable? (not last-attempt?))
          (do (retry! (.getMessage ^Exception error))
              (recur (inc attempt)))

          error
          (throw (ex-info (str "API request failed: " (.getMessage ^Exception error))
                          {:error error :attempts attempt}))

          (and (not= 200 status) retryable? (not last-attempt?))
          (do (retry! (str "HTTP " status))
              (recur (inc attempt)))

          (not= 200 status)
          (throw (ex-info (str "API error: HTTP " status)
                          {:status status :attempts attempt}))

          :else
          (let [dur-ms  (- (System/currentTimeMillis) start-ms)
                parsed  (json/read-str body :key-fn keyword)
                text    (some #(when (= "text" (:type %)) (:text %))
                              (:content parsed))
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
             :usage          (cond-> {:input-tokens  in
                                      :output-tokens out
                                      :cost-usd      (estimate-cost (:model parsed) in out)
                                      :duration-ms   dur-ms}
                               (pos? cached)  (assoc :cache-read-tokens cached)
                               (pos? created) (assoc :cache-creation-tokens created))
             :model          (:model parsed)
             :resolved-model (:model parsed)}))))))

(def ^:private cli-timeout-ms
  "Timeout in milliseconds for Claude CLI subprocess (5 minutes)."
  300000)

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
         _        (when (:isolation opts)
                    (.directory pb (io/file (System/getProperty "java.io.tmpdir"))))
         _        (when-let [extra (:env opts)]
                    (let [env (.environment pb)]
                      (doseq [[k v] extra]
                        (.put env (str k) (str v)))))
         proc     (.start pb)
         out-f    (future (slurp (.getInputStream proc)))
         err-f    (future (slurp (.getErrorStream proc)))
         done?    (.waitFor proc cli-timeout-ms TimeUnit/MILLISECONDS)
         _        (when-not done?
                    (.destroyForcibly proc)
                    (future-cancel out-f)
                    (future-cancel err-f)
                    (throw (ex-info "Claude CLI timed out" {:timeout-ms cli-timeout-ms})))
         exit     (.exitValue proc)
         out-str  @out-f
         err-str  @err-f
         dur-ms   (- (System/currentTimeMillis) start-ms)]
     (when (not= 0 exit)
       (throw (ex-info (str "Claude CLI failed: " (str/trim (or err-str "")))
                       {:exit exit})))
     (let [parsed (try (json/read-str out-str :key-fn keyword)
                       (catch Exception _ nil))]
       (if (and (map? parsed) (contains? parsed :result))
         (let [u (:usage parsed)]
           {:text           (:result parsed)
            :usage          {:input-tokens  (:input_tokens u 0)
                             :output-tokens (:output_tokens u 0)
                             :cost-usd      (:total_cost_usd parsed 0.0)
                             :duration-ms   dur-ms}
            :resolved-model (:model parsed)})
         (do (log! "WARNING: Claude CLI returned non-JSON output, using raw text with zero usage")
             {:text  out-str
              :usage (assoc zero-usage :duration-ms dur-ms)}))))))

;; --- CLI message flattening ---

(def ^:private max-prompt-chars
  "Maximum characters for a flattened CLI prompt (1 MB).
   Prevents exceeding OS argument-length limits (~2 MB on macOS/Linux)."
  1000000)

(defn flatten-messages
  "Flatten a messages vector into a single prompt string for CLI invocation.
   Prefixes each message with its role, separated by blank lines.
   When system-prefix is provided, it is prepended as a System: block.
   Truncates oldest messages if the result would exceed max-prompt-chars."
  ([messages] (flatten-messages messages nil))
  ([messages system-prefix]
   (let [format-msg (fn [{:keys [role content]}]
                      (str (case role "user" "User" "assistant" "Assistant" (str role)) ":\n" content))
         formatted  (cond-> (mapv format-msg messages)
                      system-prefix (->> (into [(str "System:\n" system-prefix)])))
         full       (str/join "\n\n" formatted)]
     (if (<= (count full) max-prompt-chars)
       full
       ;; Drop oldest messages (keeping first + last few) until under limit
       (loop [msgs (vec messages)]
         (if (<= (count msgs) 2)
           (truncate (str/join "\n\n" (cond-> (mapv format-msg msgs)
                                        system-prefix (->> (into [(str "System:\n" system-prefix)]))))
                     max-prompt-chars)
           (let [trimmed (into [(first msgs)] (subvec msgs 2))
                 result  (str/join "\n\n" (cond-> (mapv format-msg trimmed)
                                            system-prefix (->> (into [(str "System:\n" system-prefix)]))))]
             (if (<= (count result) max-prompt-chars)
               result
               (recur trimmed)))))))))

(defn invoke-cli
  "Invoke Claude via CLI. Flattens messages to a single prompt string."
  [messages opts]
  (invoke-claude-cli (flatten-messages messages (:system-prefix opts)) opts))

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
               :base-url "https://api.z.ai/api/anthropic"}
   :claude-api {:env-var  "ANTHROPIC_API_KEY"
                :base-url "https://api.anthropic.com"}})

(defn- parse-env-value
  "Strip surrounding quotes and trailing inline comments from a .env value."
  [v]
  (-> v
      str/trim
      (str/replace #"\s+#.*$" "")
      (str/replace #"^[\"']|[\"']$" "")))

(defn- read-env-from-file
  "Read a single env var from a .env file. Returns trimmed value or nil."
  [^java.io.File env-file env-var]
  (when (.exists env-file)
    (some #(when-let [[_ v] (re-matches
                             (re-pattern (str "(?:export\\s+)?" env-var "=(.+)"))
                             (str/trim %))]
             (parse-env-value v))
          (str/split-lines (slurp env-file)))))

(def ^:private trusted-env-paths
  "Trusted directories for .env files: user home, explicit project dir,
   and CWD only when deps.edn is present (dev environment indicator).
   Never reads .env from an arbitrary untrusted CWD."
  (let [home-env (java.io.File. (System/getProperty "user.home") ".env")
        proj-dir (System/getProperty "noumenon.project.dir")
        cwd      (System/getProperty "user.dir")]
    (cond-> [home-env]
      proj-dir
      (conj (java.io.File. proj-dir ".env"))
      (and (not proj-dir) cwd (.exists (java.io.File. cwd "deps.edn")))
      (conj (java.io.File. cwd ".env")))))

(defn- read-env-var
  "Read an environment variable, falling back to .env in trusted locations only.
   Checks ~/.env and the project root .env. Returns the trimmed value, or nil."
  [env-var]
  (or (System/getenv env-var)
      (some #(read-env-from-file % env-var) trusted-env-paths)))

(defn make-messages-fn
  "Create an invoke function for the given provider.
   Returns (fn [messages & [opts]]) where messages is
   [{:role \"user\"/\"assistant\" :content string} ...].
   Optional opts map supports :system (string) for prompt caching.
   Provider :glm — direct API via Z.ai proxy, reads NOUMENON_ZAI_TOKEN.
   Provider :claude-api — direct API to Anthropic, reads ANTHROPIC_API_KEY.
   Provider :claude-cli — flattens messages to single prompt string."
  [provider {:keys [model temperature max-tokens]}]
  (let [kw (provider->kw provider)]
    (if-let [{:keys [env-var base-url]} (api-provider-config kw)]
      (let [token (read-env-var env-var)]
        (when-not token
          (throw (ex-info (str env-var " environment variable is not set. Set " env-var " in your environment or in ~/.env.")
                          {:provider kw})))
        (fn invoke
          ([messages] (invoke messages nil))
          ([messages opts]
           (invoke-api messages (cond-> {:model       model
                                         :temperature temperature
                                         :max-tokens  max-tokens
                                         :base-url    base-url
                                         :auth-token  token}
                                  (:system opts) (assoc :system (:system opts)))))))
      (fn invoke
        ([messages] (invoke messages nil))
        ([messages opts]
         (invoke-cli messages (cond-> (when model {:model model})
                                (:system opts)
                                (assoc :system-prefix (:system opts)))))))))

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
  {:provider-kw (provider->kw (or provider default-provider))
   :model-id    (model-alias->id (or model default-model-alias))})

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
  "Build an isolated prompt-fn that prevents MCP discovery. For CLI providers,
   runs from /tmp to avoid .mcp.json. For API providers, returns the normal fn."
  [opts]
  (let [{:keys [provider-kw model-id]} (resolve-opts opts)]
    (if (= :claude-cli provider-kw)
      (fn [prompt]
        (invoke-claude-cli prompt (cond-> {:isolation true}
                                    model-id (assoc :model model-id))))
      (:prompt-fn (wrap-as-prompt-fn-from-opts opts)))))
