(ns noum.api
  "HTTP client for the Noumenon daemon API."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn base-url
  "Build base URL from connection info. Supports remote --host.
   Remote hosts default to https:// unless --insecure is set.
   Rejects hosts that resolve to private/link-local addresses (SSRF protection)."
  [{:keys [port host insecure]}]
  (or (when host
        (when (and (not (re-find #"^(localhost|127\.0\.0\.1)(:|$)" host))
                   (private-address? host))
          (throw (ex-info "Blocked: --host resolves to a private/internal address"
                          {:host host})))
        (if (or insecure (re-find #"^(localhost|127\.0\.0\.1)(:|$)" host))
          (str "http://" host)
          (str "https://" host)))
      (str "http://127.0.0.1:" port)))

(defn auth-headers
  "Build auth headers from connection info."
  [{:keys [token]}]
  (cond-> {"Content-Type" "application/json"}
    token (assoc "Authorization" (str "Bearer " token))))

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
            (let [data (json/parse-string (subs line 6) true)]
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
         (try (json/parse-string body-str true)
              (catch Exception _
                {:ok false :error (str "HTTP " (:status resp) ": " body-str)})))

       :else
       (json/parse-string (:body resp) true)))))

(defn get!
  "GET from the daemon API. Returns parsed JSON response."
  [conn path]
  (let [resp (http/get (str (base-url conn) path)
                       {:headers (auth-headers conn)
                        :timeout 30000
                        :throw   false})]
    (json/parse-string (:body resp) true)))

;; --- Backend lifecycle ---

(defn- load-config []
  (if (fs/exists? paths/config-path)
    (do (paths/ensure-private! paths/config-path)
        (edn/read-string (slurp paths/config-path)))
    {}))

(defn- save-config! [config]
  (fs/create-dirs (fs/parent paths/config-path))
  (spit paths/config-path (pr-str config))
  (paths/ensure-private! paths/config-path))

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
            jar-path (jar/ensure!)]
        (daemon/ensure! (merge {:jre-path jre-path :jar-path jar-path}
                               (select-keys effective [:db-dir :provider :model :token])))))))

(defn url-encode [s]
  (URLEncoder/encode (str s) "UTF-8"))
