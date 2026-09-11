(ns linear.adapter.slatedb.ffi-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.slatedb.ffi :as ffi]
   [promesa.core :as p])
  (:import
   (io.slatedb.uniffi CloseReason Error$Closed Error$Data Error$Internal Error$Invalid Error$Transaction Error$Unavailable)
   (java.nio.charset StandardCharsets)
   (java.util Arrays)))

(defn- utf8 [value]
  (.getBytes ^String value StandardCharsets/UTF_8))

(deftest classifies-slatedb-errors-as-anomalies
  (doseq [[label cause category]
          [[:transaction (Error$Transaction. "conflict") ::anomaly/conflict]
           [:unavailable (Error$Unavailable. "offline") ::anomaly/unavailable]
           [:invalid (Error$Invalid. "bad request") ::anomaly/incorrect]
           [:data (Error$Data. "corrupt") ::anomaly/fault]
           [:internal (Error$Internal. "bug") ::anomaly/fault]
           [:clean-close (Error$Closed. CloseReason/CLEAN "closed") ::anomaly/incorrect]
           [:fenced-close (Error$Closed. CloseReason/FENCED "fenced") ::anomaly/unavailable]
           [:panic-close (Error$Closed. CloseReason/PANIC "panic") ::anomaly/fault]
           [:unknown-close (Error$Closed. CloseReason/UNKNOWN "unknown") ::anomaly/fault]
           [:unexpected (IllegalStateException. "unexpected") ::anomaly/fault]]]
    (testing (name label)
      (let [error (ffi/classify-error cause {:operation :commit-transaction})]
        (is (= category (::anomaly/category (ex-data error))))
        (is (= :slatedb (:linear.error/source (ex-data error))))
        (is (= :commit-transaction (:linear.error/operation (ex-data error))))
        (is (identical? cause (ex-cause error)))))))

(deftest preserves-anomaly-bearing-errors
  (let [error (ex-info "invalid SQL" {::anomaly/category ::anomaly/incorrect})]
    (is (identical? error (ffi/classify-error error {:operation :read-values})))))

(deftest identifies-a-fenced-database-handle
  (let [error (ffi/classify-error (Error$Closed. CloseReason/FENCED "fenced") {:operation :read-snapshot-values})]
    (is (= ::ffi/fenced (:reason (ex-data error))))
    (is (ffi/fenced? error))))

(deftest await-preserves-rejection-identity
  (let [error (ex-info "unavailable" {::anomaly/category ::anomaly/unavailable})]
    (is (identical? error
                    (try
                      (ffi/await (p/rejected error))
                      (catch Exception caught
                        caught))))))

(deftest ^:integration reads-and-writes-value-collections
  (let [object-store (ffi/open-object-store "memory:///")
        database (ffi/open-database! object-store "d-ffi-test")]
    (try
      (let [transaction (ffi/await (ffi/begin-transaction database))]
        (try
          (ffi/await (ffi/write-values transaction
                                       [[(utf8 "a") (utf8 "one")]
                                        [(utf8 "b") (utf8 "two")]]))
          (ffi/await (ffi/commit-transaction transaction))
          (finally
            (ffi/close-transaction! transaction))))
      (let [transaction (ffi/await (ffi/begin-transaction database))]
        (try
          (let [values (ffi/await (ffi/read-transaction-values
                                    transaction
                                    [(utf8 "b")
                                     (utf8 "missing")
                                     (utf8 "a")]))]
            (is (= 3 (count values)))
            (is (Arrays/equals (utf8 "two") (nth values 0)))
            (is (nil? (nth values 1)))
            (is (Arrays/equals (utf8 "one") (nth values 2))))
          (finally
            (ffi/await (ffi/rollback-transaction transaction))
            (ffi/close-transaction! transaction))))
      (finally
        (ffi/close-database! database)
        (ffi/close-object-store! object-store)))))

(deftest ^:integration database-snapshot-retains-its-read-view
  (let [object-store (ffi/open-object-store "memory:///")
        database (ffi/open-database! object-store "d-snapshot-test")]
    (try
      (let [transaction (ffi/await (ffi/begin-transaction database))]
        (try
          (ffi/await (ffi/write-values transaction [[(utf8 "a") (utf8 "one")]]))
          (ffi/await (ffi/commit-transaction transaction))
          (finally
            (ffi/close-transaction! transaction))))
      (let [snapshot (ffi/await (ffi/open-snapshot database))]
        (try
          (let [transaction (ffi/await (ffi/begin-transaction database))]
            (try
              (ffi/await (ffi/write-values transaction [[(utf8 "a") (utf8 "two")]]))
              (ffi/await (ffi/commit-transaction transaction))
              (finally
                (ffi/close-transaction! transaction))))
          (let [[value] (ffi/await
                          (ffi/read-snapshot-values snapshot [(utf8 "a")]))]
            (is (Arrays/equals (utf8 "one") value)))
          (finally
            (ffi/close-snapshot! snapshot))))
      (finally
        (ffi/close-database! database)
        (ffi/close-object-store! object-store)))))
