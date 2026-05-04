(ns noumenon.daemon-test
  (:require [clojure.test :refer [deftest is testing]]
            [noumenon.cli.commands.daemon :as cd]
            [noumenon.system :as system]
            [noumenon.util :as util]))

(deftest resolve-db-dir-precedence
  (testing "Precedence: --db-dir flag > NOUMENON_DB_DIR env > ~/.noumenon/data."
    (testing "explicit --db-dir flag wins over everything"
      (with-redefs [util/env (fn [_] "/from-env")]
        (is (= "/from-flag" (cd/resolve-db-dir {:db-dir "/from-flag"})))))
    (testing "NOUMENON_DB_DIR env wins when no flag"
      (with-redefs [util/env (fn [k]
                               (when (= k "NOUMENON_DB_DIR") "/from-env"))]
        (is (= "/from-env" (cd/resolve-db-dir {})))))
    (testing "~/.noumenon/data fallback when neither flag nor env is set"
      (with-redefs [util/env (fn [_] nil)]
        (let [result (cd/resolve-db-dir {})]
          (is (.endsWith ^String result "/.noumenon/data")
              (str "expected user-home fallback path, got: " result)))))))

(deftest do-daemon-honors-env-var
  (testing "When --db-dir is absent and NOUMENON_DB_DIR is set, do-daemon
            must pass the env-var path to system/init. The previous
            inline fallback hardcoded ~/.noumenon/data BEFORE any env
            check, so NOUMENON_DB_DIR was silently ignored even though
            http.server/resolve-server-config advertised support for it."
    (let [captured (atom nil)]
      (with-redefs [util/env (fn [k]
                               (when (= k "NOUMENON_DB_DIR") "/from-env"))
                    system/init (fn [opts]
                                  (reset! captured opts)
                                  ;; Throw to short-circuit @(promise)
                                  (throw (ex-info "stop" {:opts opts})))]
        (try (cd/do-daemon {:port 0 :token "t"}) (catch Exception _))
        (is (= "/from-env" (:db-dir @captured))
            (str "expected env-var db-dir, got: " (pr-str @captured)))))))
