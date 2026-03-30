(ns noumenon.http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
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
  (let [handler (http/make-handler {:db-dir "data/datomic/" :token "secret"})
        no-auth (handler {:request-method :get :uri "/health"})
        bad-auth (handler {:request-method :get
                           :uri "/health"
                           :headers {"authorization" "Bearer wrong"}})
        good-auth (handler {:request-method :get
                            :uri "/health"
                            :headers {"authorization" "Bearer secret"}})]
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

(deftest databases-endpoint-empty
  (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        resp    (handler {:request-method :get :uri "/api/databases"})]
    (is (= 200 (:status resp)))
    (let [body (json/read-str (:body resp) :key-fn keyword)]
      (is (:ok body))
      (is (= [] (:data body))))))
