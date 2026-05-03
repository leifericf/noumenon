(ns noumenon.mcp.protocol
  "JSON-RPC framing, MCP content framing, transport helpers, and lifecycle
   methods (initialize / tools/list). The stdio loop in `run-stdio!` reads
   newline-delimited JSON-RPC requests and routes them through a dispatch
   function provided by the caller."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [noumenon.util :as util])
  (:import [java.io BufferedReader PrintWriter]))

;; --- JSON-RPC framing ---

(defn format-response [id result]
  (json/write-str {:jsonrpc "2.0" :id id :result result}))

(defn format-error [id code message]
  (json/write-str {:jsonrpc "2.0" :id id
                   :error {:code code :message message}}))

;; --- MCP content framing ---

(defn tool-result [text]
  {:content [{:type "text" :text text}]})

(defn tool-error [text]
  {:content [{:type "text" :text text}] :isError true})

;; --- Lifecycle methods ---

(defn handle-initialize [_params]
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {:listChanged false}}
   :serverInfo {:name "noumenon" :version (util/read-version)}})

(defn handle-tools-list [tools]
  {:tools tools})

(defn make-mcp-progress-fn
  "Create a progress-fn that sends MCP notifications/progress to the writer.
   Returns nil if no progressToken was provided by the client."
  [^PrintWriter writer progress-token]
  (when progress-token
    (fn [{:keys [current total message]}]
      (let [notification (json/write-str
                          {:jsonrpc "2.0"
                           :method  "notifications/progress"
                           :params  {:progressToken progress-token
                                     :progress      current
                                     :total         (or total 0)
                                     :message       message}})]
        (locking writer
          (.println writer notification))))))

;; --- Transport ---

(def ^:private max-line-bytes
  "Maximum allowed JSON-RPC line size (10 MB)."
  (* 10 1024 1024))

(defn- read-bounded-line
  "Read a line from reader, rejecting if it exceeds max-line-bytes. Returns nil on EOF."
  [^BufferedReader reader]
  (let [sb (StringBuilder.)]
    (loop []
      (let [ch (.read reader)]
        (cond
          (= ch -1)             (when (pos? (.length sb)) (.toString sb))
          (= ch (int \newline)) (.toString sb)
          (= ch (int \return))  (do (.mark reader 1)
                                    (when (not= (.read reader) (int \newline))
                                      (.reset reader))
                                    (.toString sb))
          :else
          (do (.append sb (char ch))
              (when (> (.length sb) max-line-bytes)
                (throw (ex-info "Request exceeds maximum size" {:limit max-line-bytes})))
              (recur)))))))

(defn suppress-datomic-logging! []
  (.setLevel (java.util.logging.Logger/getLogger "datomic")
             java.util.logging.Level/WARNING))

(defn run-stdio!
  "Read JSON-RPC requests from stdin one line at a time and write responses
   to stdout. `dispatch` is a 4-arg function `(dispatch id method params writer)`
   returning a JSON string response (or nil for notifications). The writer is
   exposed so dispatch handlers can emit out-of-band notifications/progress."
  [{:keys [^BufferedReader reader ^PrintWriter writer log-fn dispatch]}]
  (loop []
    (when-let [line (try
                      (read-bounded-line reader)
                      (catch Exception e
                        (log-fn "read/error" (.getMessage e))
                        (.println writer (format-error nil -32700 "Request too large"))
                        :oversized))]
      (when-not (= line :oversized)
        (when (seq line)
          (try
            (let [request (json/read-str line)
                  id      (get request "id")
                  method  (get request "method")
                  params  (or (get request "params") {})]
              (when-let [response (dispatch id method params writer)]
                (locking writer
                  (.println writer response))))
            (catch Exception e
              (log-fn "process/error" (.getMessage e))
              (.println writer (format-error nil -32700 "Parse error"))))))
      (recur))))

(defn open-stdio
  "Return {:reader :writer} backed by System/in / System/out."
  []
  {:reader (BufferedReader. (io/reader System/in))
   :writer (PrintWriter. System/out true)})
