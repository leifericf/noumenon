(ns noumenon.mcp-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [noumenon.mcp :as mcp]))

(deftest repo-path->db-name-uses-local-derivation-for-directories
  (let [tmp-root (.toFile (java.nio.file.Files/createTempDirectory "noumenon-mcp-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        repo-dir  (io/file tmp-root "mino")]
    (.mkdir repo-dir)
    (is (= "mino" (#'mcp/repo-path->db-name (.getAbsolutePath repo-dir))))))

(deftest repo-path->db-name-passes-through-db-name-input
  (is (= "mino" (#'mcp/repo-path->db-name "mino"))))

(defn- error-text [tool-result]
  (-> tool-result :content first :text))

(deftest proxy-tool-call-surfaces-curl-failure-clearly
  (testing "When the remote daemon is unreachable, curl exits non-zero
            with empty stdout. Without the guard, the handler would
            json/read-str on the empty string and bubble up
            `Remote proxy error: JSON error (end-of-file)` — utterly
            opaque. The error must name the host and hint at remediation
            so users can see WHAT failed, not just THAT a JSON parser
            saw an unexpected end-of-file."
    (with-redefs [shell/sh (fn [& _args]
                             {:exit 7
                              :out  ""
                              :err  "curl: (7) Failed to connect to 127.0.0.1 port 7892"})]
      (let [result (#'mcp/proxy-tool-call
                    "noumenon_status"
                    {"repo_path" "mino"}
                    {:host "http://127.0.0.1:7892" :token nil})
            msg    (error-text result)]
        (is (true? (:isError result)) "comes back as an MCP tool error")
        (is (string? msg))
        (is (re-find #"127\.0\.0\.1:7892" msg)
            (str "msg names the host so user knows what's unreachable; got: " msg))
        (is (not (re-find #"end-of-file|EOF" msg))
            (str "msg does NOT mention JSON-parse internals; got: " msg))))))

(deftest proxy-tool-call-surfaces-empty-body-on-zero-exit
  (testing "Even when curl succeeds (exit 0) with empty stdout — likely
            a misbehaving upstream returning 204 or a connection that
            closed before sending body — the user gets a clear message
            instead of a JSON-parse error."
    (with-redefs [shell/sh (fn [& _args]
                             {:exit 0 :out "" :err ""})]
      (let [result (#'mcp/proxy-tool-call
                    "noumenon_status"
                    {"repo_path" "mino"}
                    {:host "http://127.0.0.1:7892" :token nil})
            msg    (error-text result)]
        (is (true? (:isError result)))
        (is (not (re-find #"end-of-file|EOF" msg))
            (str "no JSON-parse leakage; got: " msg))))))
