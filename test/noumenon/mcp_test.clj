(ns noumenon.mcp-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [noumenon.mcp.proxy :as mcp]
            [org.httpkit.client :as http]))

(deftest repo-path->db-name-uses-local-derivation-for-directories
  (let [tmp-root (.toFile (java.nio.file.Files/createTempDirectory "noumenon-mcp-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo-dir  (io/file tmp-root "mino")]
    (.mkdir repo-dir)
    (is (re-matches #"mino-[0-9a-f]{12}"
                    (#'mcp/repo-path->db-name (.getAbsolutePath repo-dir))))))

(deftest repo-path->db-name-passes-through-db-name-input
  (is (= "mino" (#'mcp/repo-path->db-name "mino"))))

(defn- error-text [tool-result]
  (-> tool-result :content first :text))

(defn- mock-response
  "Build an http/request stand-in that delivers a fixed http-kit response map."
  [resp]
  (fn [_req] (doto (promise) (deliver resp))))

(deftest proxy-tool-call-surfaces-network-failure-clearly
  (testing "When the HTTP client returns :error (connect refused, DNS, etc.),
            proxy-tool-call surfaces a message that names the host and the
            underlying error rather than leaking JSON-parser internals."
    (with-redefs [http/request (mock-response
                                {:error (java.net.ConnectException.
                                         "noumenon-mcp-test-marker")})]
      (let [result (#'mcp/proxy-tool-call
                    "noumenon_status"
                    {"repo_path" "mino"}
                    {:host "http://127.0.0.1:7892" :token nil})
            msg    (error-text result)]
        (is (true? (:isError result)))
        (is (string? msg))
        (is (re-find #"127\.0\.0\.1:7892" msg)
            (str "msg names the host so user knows what's unreachable; got: " msg))
        (is (re-find #"noumenon-mcp-test-marker" msg)
            (str "underlying connection error message is included; got: " msg))
        (is (not (re-find #"end-of-file|EOF" msg))
            (str "msg does not mention JSON-parse internals; got: " msg))))))

(deftest proxy-tool-call-surfaces-empty-body-clearly
  (testing "When the daemon returns a 2xx with an empty body (e.g. a 204 or a
            connection that closed before the body was sent), the user gets a
            clear message instead of a JSON-parse error."
    (with-redefs [http/request (mock-response {:status 200 :body ""})]
      (let [result (#'mcp/proxy-tool-call
                    "noumenon_status"
                    {"repo_path" "mino"}
                    {:host "http://127.0.0.1:7892" :token nil})
            msg    (error-text result)]
        (is (true? (:isError result)))
        (is (re-find #"Empty response" msg)
            (str "msg names the empty-body case explicitly; got: " msg))
        (is (re-find #"127\.0\.0\.1:7892" msg)
            (str "msg names the host; got: " msg))
        (is (not (re-find #"end-of-file|EOF" msg))
            (str "msg does not mention JSON-parse internals; got: " msg))))))

(deftest proxy-tool-call-401-renders-reauth-message
  (testing "An HTTP 401 from the daemon should be translated into a
            user-facing remediation that names the `noum connect` command,
            even though the daemon's JSON body does not echo the status
            code (only :ok and :error)."
    (with-redefs [http/request (mock-response
                                {:status 401
                                 :body   "{\"ok\":false,\"error\":\"Unauthorized — bearer token required\"}"})]
      (let [result (#'mcp/proxy-tool-call
                    "noumenon_status"
                    {"repo_path" "mino"}
                    {:host "http://127.0.0.1:7892" :token nil})
            msg    (error-text result)]
        (is (true? (:isError result)))
        (is (re-find #"(?i)authentication failed" msg)
            (str "401 produces the friendly auth message; got: " msg))
        (is (re-find #"noum connect" msg)
            (str "msg names the remediation command; got: " msg))))))

(deftest proxy-tool-call-403-renders-permission-message
  (testing "An HTTP 403 should be translated into the admin-required message,
            independent of any :status field in the JSON body."
    (with-redefs [http/request (mock-response
                                {:status 403
                                 :body   "{\"ok\":false,\"error\":\"Forbidden — admin token required for this operation\"}"})]
      (let [result (#'mcp/proxy-tool-call
                    "noumenon_status"
                    {"repo_path" "mino"}
                    {:host "http://127.0.0.1:7892" :token nil})
            msg    (error-text result)]
        (is (true? (:isError result)))
        (is (re-find #"(?i)permission denied" msg)
            (str "403 produces the friendly permission message; got: " msg))
        (is (re-find #"(?i)admin" msg)
            (str "msg explains admin requirement; got: " msg))))))

(deftest proxy-tool-call-non-special-error-falls-through-to-body-message
  (testing "For an HTTP error that doesn't have a special case (e.g. 500),
            the daemon's `:error` text is surfaced verbatim — no friendly
            remediation, but no swallowing of detail either."
    (with-redefs [http/request (mock-response
                                {:status 500
                                 :body   "{\"ok\":false,\"error\":\"boom: something exploded\"}"})]
      (let [result (#'mcp/proxy-tool-call
                    "noumenon_status"
                    {"repo_path" "mino"}
                    {:host "http://127.0.0.1:7892" :token nil})
            msg    (error-text result)]
        (is (true? (:isError result)))
        (is (re-find #"boom: something exploded" msg)
            (str "non-special status surfaces the daemon's error verbatim; got: " msg))))))
