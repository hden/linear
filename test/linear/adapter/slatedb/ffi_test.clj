(ns linear.adapter.slatedb.ffi-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.slatedb.ffi :as ffi]
   [promesa.core :as p])
  (:import
   (io.slatedb.uniffi CloseReason Db DbBuilder Error$Closed Error$Data Error$Internal Error$Invalid Error$Transaction Error$Unavailable FlushOptions FlushType Settings)
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
    (is (= ::ffi/fenced (:reason (ex-data error))))))

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

(defn- compaction-settings []
  (with-open [defaults (Settings/_default)]
    (-> (.toJsonString defaults)
        (json/parse-string true)
        (assoc :default_ttl_millis 1)
        (assoc-in [:compactor_options :poll_interval] "10ms")
        (assoc-in [:compactor_options :commit_compacted_interval] "10ms")
        (assoc-in [:compactor_options :worker :compactions_poll_interval] "10ms")
        (assoc-in [:compactor_options :scheduler_options] {"min_compaction_sources" "2" "max_compaction_sources" "2"})
        json/generate-string
        Settings/fromJsonString)))

(deftest ^:integration compaction-removes-expired-history-and-preserves-current-and-retained-values
  (with-open [object-store (ffi/open-object-store "memory:///")
              settings (compaction-settings)
              builder (DbBuilder. "d-ttl-compaction" object-store)]
    (.withSettings builder settings)
    (let [database (ffi/await (.build builder))
          expired (utf8 "revision/expired")
          retained (utf8 "revision/retained")
          head (utf8 "head")
          page (utf8 "page/1")]
      (try
        (with-open [transaction (ffi/await (ffi/begin-transaction database))]
          (ffi/await (ffi/write-values transaction [[head (utf8 "r-current")] [page (utf8 "current")]]))
          (ffi/await (ffi/write-expiring-values transaction {:entries [[expired (utf8 "old")]] :expires-at 0}))
          (ffi/await (ffi/write-expiring-values transaction {:entries [[retained (utf8 "needed")]] :expires-at Long/MAX_VALUE}))
          (ffi/await (ffi/commit-transaction transaction)))
        (is (nil? (.expireTs (ffi/await (.getKeyValue ^Db database head)))))
        (is (nil? (.expireTs (ffi/await (.getKeyValue ^Db database page)))))
        (doseq [index (range 4)]
          (with-open [transaction (ffi/await (ffi/begin-transaction database))]
            (ffi/await (ffi/write-values transaction [[(utf8 (str "padding/" index)) (byte-array 4096)]]))
            (ffi/await (ffi/commit-transaction transaction)))
          (ffi/await (.flushWithOptions ^Db database (FlushOptions. FlushType/MEM_TABLE))))
        (let [deadline (+ (System/nanoTime) 10000000000)]
          (loop []
            (when (and (some? (ffi/await (.get ^Db database expired))) (< (System/nanoTime) deadline))
              (Thread/sleep 20)
              (recur))))
        (is (nil? (ffi/await (.get ^Db database expired))))
        (is (Arrays/equals (utf8 "needed") (ffi/await (.get ^Db database retained))))
        (is (Arrays/equals (utf8 "r-current") (ffi/await (.get ^Db database head))))
        (is (Arrays/equals (utf8 "current") (ffi/await (.get ^Db database page))))
        (finally (ffi/close-database! database))))))
