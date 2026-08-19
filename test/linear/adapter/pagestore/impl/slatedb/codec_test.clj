(ns linear.adapter.pagestore.impl.slatedb.codec-test
  (:require
   [boring.core :as cbor]
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.core :as pagestore]
   [linear.adapter.pagestore.impl.slatedb.codec :as codec]
   [linear.adapter.pagestore.impl.slatedb.fixture :as fixture]
   [taoensso.tempel :as tempel])
  (:import
   (java.security MessageDigest)))

(defn- read-error [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- encrypted-page [keychain record-key page]
  (tempel/encrypt-with-symmetric-key
    (cbor/encode {:data page})
    keychain
    {:ba-akm (.digest (MessageDigest/getInstance "SHA-256") record-key)}))

(deftest page-values-require-their-record-key
  (let [keychain fixture/keychain
        page (byte-array [42])
        source-key (codec/page-key "source" "r1")
        target-key (codec/page-key "target" "r1")
        snapshot {:head {:revision-id "r1"}
                  :raw-entries [[target-key (encrypted-page keychain source-key page)]]}]
    (fixture/with-database
      snapshot
      (fn [database]
        (let [error (read-error #(pagestore/fetch-pages-by-ids database {:ids #{"target"}}))]
          (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
          (is (= ::codec/unreadable-record (-> error ex-data :reason))))))))

(deftest unreadable-page-values-are-reported-as-anomalies
  (let [writer-keychain (tempel/keychain)
        target-key (codec/page-key "target" "r1")
        snapshot {:head {:revision-id "r1"}
                  :raw-entries [[target-key (encrypted-page writer-keychain target-key (byte-array [42]))]]}]
    (fixture/with-database
      snapshot
      (fn [database]
        (let [error (read-error #(pagestore/fetch-pages-by-ids database {:ids #{"target"}}))]
          (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
          (is (= ::codec/unreadable-record (-> error ex-data :reason))))))))
