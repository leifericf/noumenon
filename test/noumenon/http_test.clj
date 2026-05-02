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
