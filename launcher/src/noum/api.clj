(ns noum.api
  "HTTP client for the Noumenon daemon API."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [noum.daemon :as daemon]
            [noum.jre :as jre]
            [noum.jar :as jar]
            [noum.paths :as paths])
  (:import [java.net InetAddress Inet6Address URLEncoder]))

;; --- SSRF protection ---

(def ^:private blocked-ip-patterns
  [#"^127\." #"^10\." #"^172\.(1[6-9]|2[0-9]|3[01])\." #"^192\.168\."
   #"^0\." #"^169\.254\." #"^::1$" #"^fc00:" #"^fe80:" #"^fd"])

(defn- private-ip? [ip-str]
  (some #(re-find % ip-str) blocked-ip-patterns))

(defn- blocked-address? [^InetAddress addr]
  (let [ip (.getHostAddress addr)]
    (or (private-ip? ip)
        (.isLoopbackAddress addr)
        (.isLinkLocalAddress addr)
        (.isSiteLocalAddress addr)
        (when (instance? Inet6Address addr)
          (let [canon (InetAddress/getByAddress (.getAddress addr))]
            (or (.isLoopbackAddress canon)
                (.isLinkLocalAddress canon)
                (.isSiteLocalAddress canon)
                (private-ip? (.getHostAddress canon))))))))

(defn- private-address? [host-str]
  (try
    (let [host  (first (str/split host-str #":"))
          addrs (InetAddress/getAllByName host)]
      (some blocked-address? addrs))
    (catch Exception _ true)))

;; --- Connection ---

(defn- split-scheme
  "Split a host string into [scheme bare-host]. Scheme is nil when no `://` prefix."
  [host]
  (if-let [idx (str/index-of host "://")]
    [(subs host 0 idx) (subs host (+ idx 3))]
    [nil host]))

(defn- loopback-host?
  "True when the bare-host (no scheme) refers to localhost or 127.0.0.1.
   Anchored on either a port (`:`), path (`/`), or end of string so we
   don't accidentally match e.g. `localhost.attacker.com`."
  [bare-host]
  (boolean (re-find #"^(localhost|127\.0\.0\.1)(:|/|$)" bare-host)))

(defn base-url
  "Build base URL from connection info. Supports remote --host.
   Hosts may include an explicit `http(s)://` scheme; otherwise the scheme
   is derived from --insecure (or the loopback allowlist). Rejects hosts
   that resolve to private/link-local addresses (SSRF protection)."
  [{:keys [port host insecure]}]
  (or (when host
        (let [[scheme bare-host] (split-scheme host)
              loopback?          (loopback-host? bare-host)]
          (when (and (not loopback?) (private-address? bare-host))
            (throw (ex-info "Blocked: --host resolves to a private/internal address"
                            {:host host})))
          (str (or scheme
                   (if (or insecure loopback?) "http" "https"))
               "://" bare-host)))
      (str "http://127.0.0.1:" port)))

(defn auth-headers
  "Build auth headers from connection info."
  [{:keys [token]}]
  (cond-> {"Content-Type" "application/json"}
    token (assoc "Authorization" (str "Bearer " token))))

;; --- Response decoding ---

(def ^:private enum-keys
  "Response fields whose values are domain enums encoded as JSON strings.
   Restored to keywords on parse so callers can compare against keyword
   constants in idiomatic Clojure style (e.g. `:up-to-date`, `:synced`)."
  #{:status})

(defn- restore-enums
  "Re-keywordize values of known enum fields in a single map."
  [m]
  (reduce (fn [acc k]
            (if (string? (get acc k))
              (update acc k keyword)
              acc))
          m
          enum-keys))

(defn parse-body
  "Parse a JSON string into Clojure data: keywordize keys and restore known
   enum values to keywords (the symmetric counterpart to clojure.data.json's
   keyword-as-string serialization on the daemon side)."
  [s]
  (when (string? s)
    (->> (json/parse-string s true)
         (walk/postwalk #(if (map? %) (restore-enums %) %)))))

;; --- SSE ---

(defn parse-sse-events
  "Read an SSE stream, call on-progress for each progress event.
   Returns the result from the 'result' event."
  [input-stream on-progress]
  (let [result (atom nil)]
    (with-open [rdr (io/reader input-stream)]
      (loop [event-type nil]
        (when-let [line (.readLine rdr)]
          (cond
            (str/starts-with? line "event: ")
            (recur (subs line 7))

            (str/starts-with? line "data: ")
            (let [data (parse-body (subs line 6))]
              (case event-type
                "progress" (do (when on-progress (on-progress data)) (recur nil))
                "result"   (do (reset! result data) (recur nil))
                "error"    (do (reset! result {:ok false :error (:message data)}) (recur nil))
                "done"     nil
                (recur nil)))

            :else (recur event-type)))))
    @result))

;; --- API calls ---

(defn post!
  "POST to the daemon API. When on-progress is non-nil, requests SSE and feeds
   on-progress with each event. Returns parsed response."
  ([conn path body] (post! conn path body nil))
  ([conn path body on-progress]
   (let [sse?    (some? on-progress)
         headers (cond-> (auth-headers conn)
                   sse? (assoc "Accept" "text/event-stream"))
         resp    (http/post (str (base-url conn) path)
                            {:headers headers
                             :body    (json/generate-string body)
                             :timeout 600000
                             :throw   false
                             :as      (if sse? :stream :string)})]
     (cond
       (and sse? (<= 200 (:status resp) 299))
       (let [result (parse-sse-events (:body resp) on-progress)]
         (if (and (map? result) (false? (:ok result)))
           result
           {:ok true :data result}))

       sse?
       (let [body-str (slurp (:body resp))]
         (try (parse-body body-str)
              (catch Exception _
                {:ok false :error (str "HTTP " (:status resp) ": " body-str)})))

       :else
       (parse-body (:body resp))))))

(defn get!
  "GET from the daemon API. Returns parsed JSON response."
  [conn path]
  (let [resp (http/get (str (base-url conn) path)
                       {:headers (auth-headers conn)
                        :timeout 30000
                        :throw   false})]
    (parse-body (:body resp))))

;; --- Backend lifecycle ---

(defn- load-config []
  (if (fs/exists? paths/config-path)
    (do (paths/ensure-private! paths/config-path)
        (edn/read-string {:readers {}} (slurp paths/config-path)))
    {}))

(defn- save-config! [config]
  (let [parent (fs/parent paths/config-path)]
    (fs/create-dirs parent)
    (paths/ensure-private! parent)
    (spit paths/config-path (pr-str config))
    (paths/ensure-private! paths/config-path)))

(defn active-connection
  "Return the active connection map, or nil for local mode."
  []
  (let [config (load-config)
        active (:active config)
        conns  (:connections config)]
    (when (and active conns)
      (get conns active))))

(defn add-connection!
  "Add or update a named connection. Sets it as active."
  [name conn-map]
  (let [config (load-config)]
    (save-config! (-> config
                      (assoc-in [:connections name] conn-map)
                      (assoc :active name)))))

(defn remove-connection!
  "Remove a named connection. Switches to local if it was active."
  [name]
  (let [config (load-config)
        config (update config :connections dissoc name)]
    (save-config! (if (= (:active config) name)
                    (assoc config :active "local")
                    config))))

(defn set-active-connection!
  "Switch the active connection."
  [name]
  (let [config (load-config)]
    (when (and (not= name "local")
               (not (get-in config [:connections name])))
      (throw (ex-info (str "Unknown connection: " name) {:name name})))
    (save-config! (assoc config :active name))))

(defn list-connections
  "Return all connections with a :active? flag."
  []
  (let [config (load-config)
        active (:active config "local")
        conns  (or (:connections config) {})]
    (into [{"local" {:mode :local :active? (= active "local")}}]
          (map (fn [[k v]] {k (assoc v :active? (= active k))}))
          conns)))

(defn ensure-backend!
  "Returns a connection map {:port N} or {:host \"addr:port\" :token \"...\"}.
   Uses the active named connection. CLI flags override."
  [flags]
  (let [conn     (active-connection)
        effective (merge (load-config) conn flags)]
    (if-let [host (:host effective)]
      {:host host :token (:token effective) :insecure (:insecure effective)}
      (let [jre-path (jre/ensure!)
            jar-path (jar/ensure! paths/version)]
        (daemon/ensure! (merge {:jre-path jre-path :jar-path jar-path}
                               (select-keys effective [:db-dir :provider :model :token])))))))

;; --- Launcher-local settings ---

(def ^:private launcher-setting-prefixes
  "Setting key prefixes that live in the launcher's config (not the daemon).
   Each key is namespaced — e.g. :federation/auto-route."
  #{"federation"})

(defn launcher-setting-key?
  "True if this setting key is a launcher-side concern (not daemon-side)."
  [k]
  (let [ns-part (cond
                  (keyword? k) (namespace k)
                  (string? k)  (let [parts (str/split k #"/" 2)]
                                 (when (= 2 (count parts)) (first parts))))]
    (contains? launcher-setting-prefixes ns-part)))

(defn launcher-setting
  "Read a launcher-local setting from ~/.noumenon/config.edn."
  ([k] (launcher-setting k nil))
  ([k default]
   (let [config (load-config)]
     (get-in config [:launcher-settings (keyword k)] default))))

(defn set-launcher-setting!
  "Persist a launcher-local setting to ~/.noumenon/config.edn."
  [k v]
  (save-config! (assoc-in (load-config) [:launcher-settings (keyword k)] v)))

;; --- Federation auto-routing ---

(defn- git-cmd
  "Run a git command in cwd; return trimmed stdout or nil on failure."
  [cwd & args]
  (try
    (let [proc (.exec (Runtime/getRuntime)
                      (into-array String (into ["git" "-C" (str cwd)] args)))
          _    (.waitFor proc)]
      (when (zero? (.exitValue proc))
        (str/trim (slurp (.getInputStream proc)))))
    (catch Exception _ nil)))

(defn- local-head-sha [cwd]   (git-cmd cwd "rev-parse" "HEAD"))
(defn- local-branch [cwd]     (or (git-cmd cwd "symbolic-ref" "--short" "HEAD")
                                  (some-> (local-head-sha cwd) (subs 0 (min 7 40)))))

(defn- path->db-name
  "Mirror server-side util/derive-db-name for the launcher."
  [path]
  (let [f (java.io.File. (str path))]
    (when (.exists f)
      (-> (.getCanonicalPath f) (str/replace #"/+$" "") (str/split #"/") last
          (str/replace #"[^a-zA-Z0-9\-_.]" "")))))

(defn- hosted-trunk-head-sha
  "GET /api/status/<db-name> on the hosted daemon and return :head-sha."
  [conn db-name]
  (try
    (when db-name
      (let [resp (get! conn (str "/api/status/" db-name))]
        (when (:ok resp) (get-in resp [:data :head-sha]))))
    (catch Exception _ nil)))

(defn detect-federation-context
  "When the active connection is hosted and local HEAD has diverged from
   the trunk DB's :repo/head-sha, return {:basis-sha <hosted-head>
   :branch <local-branch>}. Otherwise nil. Catches and swallows all
   transient errors so a missing daemon, missing git, or unimported
   trunk gracefully falls through to non-federated behavior."
  [cwd conn]
  (try
    (when (and conn (:host conn))
      (let [db-name      (path->db-name cwd)
            local-head   (local-head-sha cwd)
            trunk-head   (hosted-trunk-head-sha conn db-name)]
        (when (and (string? local-head)
                   (string? trunk-head)
                   (not= local-head trunk-head)
                   (re-matches #"[a-f0-9]{40}" trunk-head))
          {:basis-sha trunk-head
           :branch    (local-branch cwd)})))
    (catch Exception _ nil)))

(defn url-encode [s]
  (URLEncoder/encode (str s) "UTF-8"))
