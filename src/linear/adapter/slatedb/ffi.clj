(ns linear.adapter.slatedb.ffi
  (:refer-clojure :exclude [await])
  (:require
   [cognitect.anomalies :as anomaly]
   [promesa.core :as p]
   [promesa.exec :as px])
  (:import
   (io.slatedb.uniffi CloseReason Db DbBuilder DbSnapshot DbTransaction Error$Closed Error$Data Error$Internal Error$Invalid Error$Transaction Error$Unavailable IsolationLevel ObjectStore PutOptions Ttl$ExpireAtMillis Ttl$NoExpiry WriteHandle)
   (java.util.concurrent CompletionException ExecutionException)))

(defn- closed-category [^Error$Closed error]
  (condp identical? (.reason error)
    CloseReason/CLEAN ::anomaly/incorrect
    CloseReason/FENCED ::anomaly/unavailable
    CloseReason/PANIC ::anomaly/fault
    CloseReason/UNKNOWN ::anomaly/fault
    ::anomaly/fault))

(defn- error-category [error]
  (cond
    (instance? Error$Transaction error) ::anomaly/conflict
    (instance? Error$Unavailable error) ::anomaly/unavailable
    (instance? Error$Invalid error) ::anomaly/incorrect
    (instance? Error$Closed error) (closed-category error)
    (instance? Error$Data error) ::anomaly/fault
    (instance? Error$Internal error) ::anomaly/fault
    :else ::anomaly/fault))

(defn classify-error [error {:keys [operation]}]
  (if (::anomaly/category (ex-data error))
    error
    (ex-info "SlateDB operation failed"
             (cond-> {::anomaly/category (error-category error)
                      :linear.error/source :slatedb
                      :linear.error/operation operation}
               (and (instance? Error$Closed error)
                    (identical? CloseReason/FENCED (.reason ^Error$Closed error)))
               (assoc :reason ::fenced))
             error)))

(defn- unwrap-completion [error]
  (if (or (instance? CompletionException error)
          (instance? ExecutionException error))
    (recur (or (ex-cause error) error))
    error))

(defn- invoke [operation f]
  (-> (try
        (p/promise (f))
        (catch Exception error
          (p/rejected error)))
      (p/catch (fn [error]
                 (throw (classify-error (unwrap-completion error) {:operation operation}))))))

(defn await [promise]
  (try
    (px/await! promise)
    (catch Exception error
      (throw (unwrap-completion error)))))

(defn- read-values-impl [operation read-value keys]
  (reduce
    (fn [values-promise key]
      (p/then values-promise
              (fn [values]
                (p/then (invoke operation #(read-value key))
                        #(conj values %)))))
    (p/resolved [])
    keys))

(defn- write-values-impl [transaction entries options]
  (reduce
    (fn [write-promise [key value]]
      (p/then write-promise
              (fn [_previous-write]
                (invoke :write-values #(.putWithOptions ^DbTransaction transaction key value options)))))
    (p/resolved nil)
    entries))

(defn open-object-store [url]
  (try
    (ObjectStore/resolve url)
    (catch LinkageError error
      (throw (classify-error error {:operation :open-object-store})))
    (catch Exception error
      (throw (classify-error error {:operation :open-object-store})))))

(defn close-object-store! [object-store]
  (try
    (.close ^ObjectStore object-store)
    (catch Exception error
      (throw (classify-error error {:operation :close-object-store}))))
  nil)

(defn open-database! [object-store database-id]
  (with-open [builder (DbBuilder. ^String database-id ^ObjectStore object-store)]
    (await (invoke :open-database #(.build builder)))))

(defn close-database! [database]
  (let [shutdown-error (try
                         (await (invoke :shutdown-database #(.shutdown ^Db database)))
                         nil
                         (catch Exception error
                           error))
        close-error (try
                      (.close ^Db database)
                      nil
                      (catch Exception error
                        error))]
    (cond
      shutdown-error
      (do
        (when close-error
          (.addSuppressed ^Exception shutdown-error ^Exception close-error))
        (throw shutdown-error))

      close-error
      (throw close-error)))
  nil)

(defn usable-database? [database]
  (try
    (nil? (-> ^Db database .status .closeReason))
    (catch Exception _
      false)))

(defn open-snapshot [database]
  (invoke :open-snapshot #(.snapshot ^Db database)))

(defn close-snapshot! [snapshot]
  (try
    (.close ^DbSnapshot snapshot)
    (catch Exception error
      (throw (classify-error error {:operation :close-snapshot}))))
  nil)

(defn read-snapshot-values [snapshot keys]
  (read-values-impl :read-snapshot-values #(.get ^DbSnapshot snapshot %) keys))

(defn begin-transaction [database]
  (invoke :begin-transaction
          #(.begin ^Db database IsolationLevel/SERIALIZABLE_SNAPSHOT)))

(defn read-transaction-values [transaction keys]
  (read-values-impl :read-transaction-values
                    #(.get ^DbTransaction transaction %)
                    keys))

(defn write-values [transaction entries]
  (write-values-impl transaction entries (PutOptions. (Ttl$NoExpiry.))))

(defn write-expiring-values [transaction {:keys [entries expires-at]}]
  (write-values-impl transaction entries (PutOptions. (Ttl$ExpireAtMillis. (long expires-at)))))

(defn commit-transaction [transaction]
  (p/then (invoke :commit-transaction #(.commit ^DbTransaction transaction))
          (fn [handle]
            (-> (invoke :commit-transaction #(.awaitDurable ^WriteHandle handle))
                (p/finally (fn [_value _error]
                             (.close ^WriteHandle handle)))))))

(defn rollback-transaction [transaction]
  (invoke :rollback-transaction #(.rollback ^DbTransaction transaction)))

(defn close-transaction! [transaction]
  (try
    (.close ^DbTransaction transaction)
    (catch Exception error
      (throw (classify-error error {:operation :close-transaction}))))
  nil)
