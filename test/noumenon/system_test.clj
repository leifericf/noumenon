(ns noumenon.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [noumenon.concurrency :as cc]
            [noumenon.system :as system]
            [noumenon.util :as util]))

(defn- with-tmp-system [opts f]
  (let [tmp     (str (System/getProperty "java.io.tmpdir")
                     "/noumenon-system-test-" (random-uuid))
        sys     (system/init (merge {:port 0 :bind "127.0.0.1" :db-dir tmp} opts))]
    (try (f sys) (finally (system/halt! sys)))))

(deftest semaphore-permits-honor-system-config
  (testing "max-llm-concurrency from system/init reaches the running semaphore"
    (with-tmp-system {:max-llm-concurrency 7}
      (fn [_sys]
        (is (= 7 (cc/llm-available-permits)))))))

(deftest semaphore-permits-honor-env-var
  (testing "NOUMENON_MAX_LLM_CONCURRENCY flows through system/config to the semaphore"
    (with-redefs [util/env-int (fn [n]
                                 (when (= n "NOUMENON_MAX_LLM_CONCURRENCY") 5))]
      (with-tmp-system {}
        (fn [sys]
          (is (= 5 (cc/llm-available-permits)))
          (is (= {:max-permits 5} (get sys :noumenon/llm-semaphore))))))))

(deftest http-server-depends-on-every-shared-infra-component
  (testing "every shared-infra component is an explicit dependency of :noumenon/http-server"
    (let [cfg  (system/config {:port 0 :bind "127.0.0.1" :db-dir "/tmp/sysprobe"})
          deps (-> cfg ig/dependency-graph :dependencies (get :noumenon/http-server))]
      (doseq [k [:noumenon/datomic-conns
                 :noumenon/llm-semaphore
                 :noumenon/embed-cache
                 :noumenon/completion-cache
                 :noumenon/agent-sessions]]
        (is (contains? deps k) (str k " should be a dependency of :noumenon/http-server"))))))
