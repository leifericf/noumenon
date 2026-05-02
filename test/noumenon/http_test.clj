(ns noumenon.http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [noumenon.http :as http]))

(deftest health-endpoint
  (let [handler (http/make-handler {:db-dir    "data/datomic/"
                                    :started-at (System/currentTimeMillis)})
        resp    (handler {:request-method :get :uri "/health"})]
    (is (= 200 (:status resp)))
    (let [body (json/read-str (:body resp) :key-fn keyword)]
      (is (:ok body))
      (is (= "ok" (get-in body [:data :status]))))))

(deftest not-found
  (let [handler (http/make-handler {:db-dir "data/datomic/"})
        resp    (handler {:request-method :get :uri "/nonexistent"})]
    (is (= 404 (:status resp)))))

(deftest auth-required-when-token-set
  (let [db-dir  (str (System/getProperty "user.dir") "/data/datomic/")
        handler (http/make-handler {:db-dir db-dir :token "secret"})
        ;; /health is always public (load balancer probes)
        health-no-auth (handler {:request-method :get :uri "/health"})
        ;; API endpoints require auth
        no-auth (handler {:request-method :get :uri "/api/databases"})
        bad-auth (handler {:request-method :get
                           :uri "/api/databases"
                           :headers {"authorization" "Bearer wrong"}})
        good-auth (handler {:request-method :get
                            :uri "/api/databases"
                            :headers {"authorization" "Bearer secret"}})]
    (is (= 200 (:status health-no-auth)))
    (is (= 401 (:status no-auth)))
    (is (= 401 (:status bad-auth)))
    (is (= 200 (:status good-auth)))))

(deftest routing-method-mismatch
  (let [handler (http/make-handler {:db-dir "data/datomic/"})
        resp    (handler {:request-method :post :uri "/health"})]
    (is (= 404 (:status resp)) "GET-only endpoint should not match POST")))

(deftest path-traversal-in-db-name-rejected
  (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        traverse (handler {:request-method :delete :uri "/api/databases/.."})
        dots     (handler {:request-method :delete :uri "/api/databases/..."})
        blank    (handler {:request-method :get :uri "/api/status/.."})]
    (is (= 400 (:status traverse)) ".. should be rejected")
    (is (= 400 (:status dots)) "... should be rejected")
    (is (= 400 (:status blank)) ".. in status should be rejected")))

(deftest delete-meta-db-rejected
  (testing "DELETE /api/databases/noumenon-internal must not let callers
            wipe the meta DB — it stores tokens, settings, prompts, rules,
            ask sessions, and benchmark history, and the daemon's cached
            :meta-conn would silently break for the rest of the process
            lifetime."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          resp    (handler {:request-method :delete :uri "/api/databases/noumenon-internal"})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp)))
      (is (re-find #"reserved" (str (:error body)))
          (str "expected 'reserved' wording in error, got: " (:error body))))))

(deftest url-encoded-path-params-decoded
  (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        ;; Use a URL-encoded db name like "my%20repo" -> should decode to "my repo"
        ;; which will fail validation (spaces stripped by derive-db-name) but the
        ;; error should reference the decoded name, not the encoded one
        resp    (handler {:request-method :get :uri "/api/status/my%20repo"})
        body    (json/read-str (:body resp) :key-fn keyword)]
    ;; Should get 404 (db not found) rather than some other error
    (is (= 404 (:status resp)))
    (is (re-find #"my repo" (:error body)) "db-name should be URL-decoded")))

(deftest databases-endpoint-empty
  (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        resp    (handler {:request-method :get :uri "/api/databases"})]
    (is (= 200 (:status resp)))
    (let [body (json/read-str (:body resp) :key-fn keyword)]
      (is (:ok body))
      (is (= [] (:data body))))))

(defn- post-with-body
  "Build a Ring-shaped POST request with a JSON body."
  [uri body-map]
  {:request-method :post
   :uri            uri
   :headers        {}
   :body           (java.io.ByteArrayInputStream.
                    (.getBytes ^String (json/write-str body-map) "UTF-8"))})

(deftest query_name-length-cap-uniform-across-endpoints
  (let [handler   (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        long-name (apply str (repeat 257 \a))
        send      (fn [uri extra]
                    (let [resp (handler (post-with-body uri (merge {:repo_path "any"
                                                                    :query_name long-name}
                                                                   extra)))
                          body (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error body)}))]
    (testing "POST /api/query rejects 257-char query_name with the shared
              max-length message — earlier the unknown-query lookup echoed
              the 300-char name back unchecked"
      (let [{:keys [status error]} (send "/api/query" {})]
        (is (= 400 status))
        (is (re-find #"query_name exceeds maximum length" (str error))
            error)))
    (testing "POST /api/query-as-of also rejects oversized query_name
              before the as_of validation, so the failure mode is uniform"
      (let [{:keys [status error]} (send "/api/query-as-of" {:as_of "2026-01-01T00:00:00Z"})]
        (is (= 400 status))
        (is (re-find #"query_name exceeds maximum length" (str error))
            error)))
    (testing "POST /api/query-federated continues to reject oversized
              query_name (this endpoint already had the cap; the test
              guards against regression as the constant gets centralized)"
      (let [{:keys [status error]} (send "/api/query-federated"
                                         {:basis_sha "31389f9382cbc440aafeeb1b854c27830cf1e26f"})]
        (is (= 400 status))
        (is (re-find #"query_name exceeds maximum length" (str error))
            error)))))

(deftest ask-feedback-on-missing-session-returns-404
  (testing "POST /api/ask/sessions/<unknown>/feedback used to write
            feedback to a non-existent session and return 200, leaving
            an orphan attribute set in the meta DB and lying to the
            client. The handler now looks up the session first and
            404s on miss, matching handle-ask-session-detail."
    (let [tmp     (str "/tmp/noumenon-feedback-test-" (System/currentTimeMillis))
          handler (http/make-handler {:db-dir tmp})
          resp    (handler {:request-method :post
                            :uri            "/api/ask/sessions/ghostly/feedback"
                            :headers        {}
                            :body (java.io.ByteArrayInputStream.
                                   (.getBytes (json/write-str {:feedback "positive"}) "UTF-8"))})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 404 (:status resp)))
      (is (re-find #"(?i)session.*not found|not found" (str (:error body)))
          (str "expected 'not found' wording, got: " (:error body))))))

(deftest malformed-json-body-returns-400
  (testing "POST with a syntactically broken JSON body must surface as
            400 'Invalid JSON body', not as a generic 500. The previous
            parse-json-body let json/read-str's exception flow up into
            the make-handler catch-all, where it became 'Internal
            server error' even though the issue was wholly the
            client's input."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          request {:request-method :post
                   :uri            "/api/import"
                   :headers        {}
                   :body           (java.io.ByteArrayInputStream.
                                    (.getBytes "{not json" "UTF-8"))}
          resp    (handler request)
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp)))
      (is (re-find #"(?i)invalid json" (str (:error body)))
          (str "expected 'Invalid JSON' wording, got: " (:error body))))))

(deftest with-repo-rejects-bad-repo_path-shapes
  (testing "with-repo runs type+length+blank checks before FS work, so
            non-string / oversized / empty repo_path values get a clean
            400 instead of leaking ClassCastException as 500. Covers
            every endpoint that funnels through with-repo (~16); the
            previous fix only patched delta-ensure."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          send    (fn [body]
                    (let [resp (handler (post-with-body "/api/import" body))
                          parsed (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error parsed)}))]
      (testing "Long (number) is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path 42})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "vector is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path ["a"]})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "object is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path {:a 1}})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "boolean is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path true})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "empty string is rejected with 400 (was 500 because empty
                fell through to the bare-db-name branch and shelled out
                to git log against db://)"
        (let [{:keys [status error]} (send {:repo_path ""})]
          (is (= 400 status))
          (is (re-find #"(?i)blank|empty|required" (str error)) error)))
      (testing "repo_path over the 4096-char length cap is rejected with
                400, not echoed back in a 404 — request-amplification
                mitigation. (Anything > max-repo-path-len triggers the
                cap; 5 KB is enough to exercise the gate without
                ballooning failure output.)"
        (let [{:keys [status error]} (send {:repo_path (apply str (repeat 5000 \A))})]
          (is (= 400 status))
          (is (re-find #"exceeds maximum length" (str error)) error))))))

(deftest federated-delta-opts-derives-parent-metadata
  (let [opts-fn @#'http/federated-delta-opts]
    (is (= {:parent-db-name "myrepo"
            :branch-name    "feat"
            :parent-host    "noum.example.com:8765"}
           (opts-fn {:headers {"host" "noum.example.com:8765"}}
                    "myrepo"
                    "feat"))
        "auto-derives parent-db-name from the resolved trunk repo and
         parent-host from the request's Host header, so federated query
         calls leave a breadcrumb on the delta's branch entity even when
         the caller didn't pass them explicitly")
    (is (= {:parent-db-name "myrepo"}
           (opts-fn {:headers {}} "myrepo" nil))
        "missing Host header (unusual proxy setup) and missing branch —
         parent-host is omitted, parent-db-name is still set; the
         breadcrumb is informational and partial is acceptable")))
