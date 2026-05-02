(ns noumenon.promotion-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [noumenon.promotion :as promotion]
            [noumenon.test-helpers :as th]))

(def ^:private blob-x "aaa1111111111111111111111111111111111111")
(def ^:private blob-y "bbb2222222222222222222222222222222222222")
(def ^:private prompt-h "ph-current")
(def ^:private model-v "model-v1")

(defn- analyze-tx
  "Build a tx that asserts file analysis attrs + matching provenance."
  [path summary]
  [{:file/path path :sem/summary summary :sem/complexity :simple :arch/layer :core}
   {:db/id "datomic.tx"
    :tx/op :analyze :tx/source :llm
    :prov/prompt-hash prompt-h
    :prov/model-version model-v}])

(deftest find-cached-analysis-misses-without-blob
  (let [conn (th/make-test-conn "promo-miss")]
    (d/transact conn {:tx-data [{:file/path "src/a.clj" :file/blob-sha blob-x}]})
    (d/transact conn {:tx-data (analyze-tx "src/a.clj" "donor summary")})
    (testing "different blob → no donor"
      (is (nil? (promotion/find-cached-analysis (d/db conn) blob-y prompt-h model-v))))
    (testing "different prompt → no donor"
      (is (nil? (promotion/find-cached-analysis (d/db conn) blob-x "other-ph" model-v))))
    (testing "different model → no donor"
      (is (nil? (promotion/find-cached-analysis (d/db conn) blob-x prompt-h "other-mv"))))))

(deftest find-cached-analysis-finds-existing-donor
  (let [conn (th/make-test-conn "promo-hit")]
    (d/transact conn {:tx-data [{:file/path "src/donor.clj" :file/blob-sha blob-x}]})
    (d/transact conn {:tx-data (analyze-tx "src/donor.clj" "donor summary")})
    (let [donor (promotion/find-cached-analysis (d/db conn) blob-x prompt-h model-v)]
      (is (some? donor))
      (is (= "donor summary" (:sem/summary donor)))
      (is (= :simple (:sem/complexity donor)))
      (is (= :core (:arch/layer donor)))
      (is (some? (:donor-tx donor))))))

(deftest promote-tx-data-shape
  (let [donor {:donor-tx 12345 :sem/summary "x" :sem/complexity :simple :arch/layer :core}
        out   (promotion/promote-tx-data "src/recipient.clj" donor)
        [file-tx tx-meta] out]
    (is (= "src/recipient.clj" (:file/path file-tx)))
    (is (= "x" (:sem/summary file-tx)))
    (is (= :simple (:sem/complexity file-tx)))
    (is (= :core (:arch/layer file-tx)))
    (is (= :promote (:tx/op tx-meta)))
    (is (= :promoted (:tx/source tx-meta)))
    (is (= 12345 (:prov/promoted-from tx-meta)))))

(deftest promote-tx-data-skips-unset-donor-attrs
  (let [donor {:donor-tx 1 :sem/summary "x"}
        [file-tx _] (promotion/promote-tx-data "src/r.clj" donor)]
    (is (= #{:file/path :sem/summary} (set (keys file-tx))))))

(deftest promote-tx-data-cross-db-records-db-name
  (testing "donor-db-name set → tx-meta drops :prov/promoted-from (foreign tx
  ids are meaningless in the recipient DB) and instead records the
  donor's db-name as a breadcrumb"
    (let [donor {:donor-tx 99 :sem/summary "x"
                 :donor-db-name "noumenon-trunk"}
          [_ tx-meta] (promotion/promote-tx-data "src/r.clj" donor)]
      (is (nil? (:prov/promoted-from tx-meta)))
      (is (= "noumenon-trunk" (:prov/promoted-from-db-name tx-meta)))
      (is (= :promote (:tx/op tx-meta)))
      (is (= :promoted (:tx/source tx-meta))))))

(deftest promote!-end-to-end
  (let [conn (th/make-test-conn "promo-e2e")]
    ;; Donor: src/donor.clj at blob-x, analyzed
    (d/transact conn {:tx-data [{:file/path "src/donor.clj" :file/blob-sha blob-x}]})
    (d/transact conn {:tx-data (analyze-tx "src/donor.clj" "donor analysis")})
    ;; Recipient: src/recipient.clj at blob-x, NOT yet analyzed
    (d/transact conn {:tx-data [{:file/path "src/recipient.clj" :file/blob-sha blob-x}]})
    (let [donor   (promotion/find-cached-analysis (d/db conn) blob-x prompt-h model-v)
          result  (promotion/promote! conn "src/recipient.clj" donor)
          db'     (d/db conn)
          rcpt    (d/pull db' '[*] [:file/path "src/recipient.clj"])]
      (is (= :promoted (:status result)))
      (is (= "donor analysis" (:sem/summary rcpt))
          "recipient now carries donor analysis")
      (testing "recipient's analysis tx points to donor via :prov/promoted-from"
        (let [rcpt-tx (ffirst (d/q '[:find ?tx
                                     :where
                                     [?f :file/path "src/recipient.clj"]
                                     [?f :sem/summary _ ?tx]]
                                   db'))
              prov    (d/pull db' [{:prov/promoted-from [:db/id]}] rcpt-tx)]
          (is (some? (:prov/promoted-from prov))))))))

(deftest promote!-noop-on-nil-donor
  (let [conn (th/make-test-conn "promo-noop")]
    (is (nil? (promotion/promote! conn "src/anything.clj" nil)))))

(deftest find-cached-analysis-rejects-donor-db-without-name
  (testing "passing :donor-db without :donor-db-name throws — without this
            guard the lookup would treat the result as same-DB and record a
            dangling :prov/promoted-from ref pointing into the donor DB
            (where the tx-id is meaningful) from the recipient DB (where
            it isn't). Lands defensively before cross-DB promotion is
            wired into a production caller"
    (let [recipient (th/make-test-conn "promo-paired-args-recipient")
          donor     (th/make-test-conn "promo-paired-args-donor")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"donor-db-name"
           (promotion/find-cached-analysis
            (d/db recipient) blob-x prompt-h model-v
            {:donor-db (d/db donor)}))))))

(deftest find-cached-analysis-cross-db
  (testing "donor lives in trunk-conn; recipient lives in delta-conn — the
  lookup uses :donor-db, the promote happens against the recipient"
    (let [trunk (th/make-test-conn "promo-cross-trunk")
          delta (th/make-test-conn "promo-cross-delta")]
      (d/transact trunk {:tx-data [{:file/path "src/x.clj" :file/blob-sha blob-x}]})
      (d/transact trunk {:tx-data (analyze-tx "src/x.clj" "trunk's analysis")})
      (d/transact delta {:tx-data [{:file/path "src/x.clj" :file/blob-sha blob-x}]})
      (let [donor (promotion/find-cached-analysis
                   (d/db delta) blob-x prompt-h model-v
                   {:donor-db (d/db trunk) :donor-db-name "noumenon-trunk"})]
        (is (some? donor) "trunk donor found via cross-DB scan")
        (is (= "trunk's analysis" (:sem/summary donor)))
        (is (= "noumenon-trunk" (:donor-db-name donor))
            "donor map carries the donor-db-name so promote-tx-data can record it"))
      (testing "miss: trunk has no donor with that prompt"
        (is (nil? (promotion/find-cached-analysis
                   (d/db delta) blob-x "different-ph" model-v
                   {:donor-db (d/db trunk) :donor-db-name "noumenon-trunk"})))))))

(deftest promote!-cross-db-end-to-end
  (let [trunk (th/make-test-conn "promo-x-trunk")
        delta (th/make-test-conn "promo-x-delta")]
    (d/transact trunk {:tx-data [{:file/path "src/y.clj" :file/blob-sha blob-x}]})
    (d/transact trunk {:tx-data (analyze-tx "src/y.clj" "trunk analysis")})
    (d/transact delta {:tx-data [{:file/path "src/y.clj" :file/blob-sha blob-x}]})
    (let [donor  (promotion/find-cached-analysis
                  (d/db delta) blob-x prompt-h model-v
                  {:donor-db (d/db trunk) :donor-db-name "noumenon-trunk"})
          result (promotion/promote! delta "src/y.clj" donor)
          db'    (d/db delta)]
      (is (= :promoted (:status result)))
      (is (= "trunk analysis"
             (:sem/summary (d/pull db' [:sem/summary] [:file/path "src/y.clj"]))))
      (testing "the recipient's tx records :prov/promoted-from-db-name and
      DROPS :prov/promoted-from (foreign tx-ids are meaningless here)"
        (let [rcpt-tx (ffirst (d/q '[:find ?tx
                                     :where
                                     [?f :file/path "src/y.clj"]
                                     [?f :sem/summary _ ?tx]]
                                   db'))
              tx-meta (d/pull db' [:prov/promoted-from-db-name
                                   {:prov/promoted-from [:db/id]}] rcpt-tx)]
          (is (= "noumenon-trunk" (:prov/promoted-from-db-name tx-meta)))
          (is (nil? (:prov/promoted-from tx-meta))))))))

;; --- analyze-file! integration: promotion bypasses the LLM ---

(defn- llm-counter
  "Returns [counter-atom invoke-llm-fn]. The fn always throws so a hit on
   promotion (which avoids invoke-llm) is the only way analyze-file! can
   return :promoted without erroring."
  []
  (let [calls (atom 0)]
    [calls (fn [_prompt]
             (swap! calls inc)
             (throw (ex-info "LLM should not have been called" {})))]))

(deftest analyze-file!-promotes-on-hit
  (let [conn (th/make-test-conn "promo-analyze")
        [calls _llm] (llm-counter)]
    ;; Donor: src/donor.clj at blob-x, fully analyzed
    (d/transact conn {:tx-data [{:file/path "src/donor.clj" :file/blob-sha blob-x
                                 :file/lang :clojure :file/size 100}]})
    (d/transact conn {:tx-data (analyze-tx "src/donor.clj" "donor's analysis")})
    ;; Recipient: src/recipient.clj at blob-x, NOT analyzed
    (d/transact conn {:tx-data [{:file/path "src/recipient.clj" :file/blob-sha blob-x
                                 :file/lang :clojure :file/size 100}]})
    (let [opts   {:provider "test" :model-id model-v
                  :prompt-template "irrelevant"
                  :prompt-hash-val prompt-h
                  :invoke-llm (fn [_p] (swap! calls inc) (throw (ex-info "should not call" {})))}
          _ (require 'noumenon.analyze)
          analyze (resolve 'noumenon.analyze/analyze-file!)
          out (analyze conn "/tmp/fake-repo" {:file/path "src/recipient.clj" :file/lang :clojure} opts)]
      (is (= :promoted (:status out)))
      (is (zero? @calls) "LLM was not invoked")
      (is (= "donor's analysis"
             (:sem/summary (d/pull (d/db conn) [:sem/summary] [:file/path "src/recipient.clj"])))))))

(deftest analyze-file!-no-promote-flag-bypasses-cache
  (let [conn (th/make-test-conn "promo-flag-off")
        [_calls llm] (llm-counter)]
    (d/transact conn {:tx-data [{:file/path "src/donor.clj" :file/blob-sha blob-x
                                 :file/lang :clojure :file/size 100}]})
    (d/transact conn {:tx-data (analyze-tx "src/donor.clj" "donor")})
    (d/transact conn {:tx-data [{:file/path "src/recipient.clj" :file/blob-sha blob-x
                                 :file/lang :clojure :file/size 100}]})
    (let [analyze (do (require 'noumenon.analyze) (resolve 'noumenon.analyze/analyze-file!))
          opts {:provider "test" :model-id model-v
                :prompt-template "irrelevant" :prompt-hash-val prompt-h
                :invoke-llm llm :no-promote? true}
          _out (analyze conn "/tmp/fake-repo"
                        {:file/path "src/recipient.clj" :file/lang :clojure} opts)]
      ;; With --no-promote we bypass the donor lookup and fall through to the
      ;; LLM path (which fails on the fake repo); the recipient must NOT have
      ;; been promoted.
      (is (nil? (:sem/summary (d/pull (d/db conn) [:sem/summary]
                                      [:file/path "src/recipient.clj"])))
          "recipient was not promoted because --no-promote bypassed the cache"))))
