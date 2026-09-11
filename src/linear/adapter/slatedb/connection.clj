(ns linear.adapter.slatedb.connection
  (:require
   [linear.adapter.slatedb.ffi :as ffi])
  (:import
   (java.lang AutoCloseable)
   (org.apache.commons.pool2 BaseKeyedPooledObjectFactory PooledObject)
   (org.apache.commons.pool2.impl DefaultPooledObject GenericKeyedObjectPool GenericKeyedObjectPoolConfig)))

(defrecord Connection
           [borrow-database return-database close-resources])

(defn connection [options]
  (map->Connection options))

(defrecord ^:private DatabaseLease [raw-database release!]
  AutoCloseable
  (close [_]
    (release!)))

(defn- add-suppressed! [error suppressed]
  (when suppressed
    (.addSuppressed ^Exception error ^Exception suppressed))
  error)

(defn- close-resources! [pool object-store]
  (let [pool-error (try
                     (.close ^GenericKeyedObjectPool pool)
                     nil
                     (catch Exception error
                       error))
        object-store-error (try
                             (ffi/close-object-store! object-store)
                             nil
                             (catch Exception error
                               error))]
    (cond
      pool-error (throw (add-suppressed! pool-error object-store-error))
      object-store-error (throw object-store-error))))

(defn- database-pool-factory [object-store]
  (proxy [BaseKeyedPooledObjectFactory] []
    (create [database-id]
      (ffi/open-database! object-store database-id))
    (wrap [database]
      (DefaultPooledObject. database))
    (validateObject [_ pooled-database]
      (ffi/usable-database? (.getObject ^PooledObject pooled-database)))
    (destroyObject [_ pooled-database]
      (ffi/close-database! (.getObject ^PooledObject pooled-database)))))

(defn- database-pool [object-store max-open-databases]
  (let [config (GenericKeyedObjectPoolConfig.)]
    (.setMaxTotal config max-open-databases)
    (.setMaxTotalPerKey config 1)
    (.setMaxIdlePerKey config 1)
    (.setTestOnReturn config true)
    (GenericKeyedObjectPool. (database-pool-factory object-store) config)))

(defn open
  {:malli/schema [:->
                  [:map
                   [:object-store-url :string]
                   [:max-open-databases pos-int?]]
                  [:fn #(instance? Connection %)]]}
  [{:keys [object-store-url max-open-databases]}]
  (let [object-store (ffi/open-object-store object-store-url)]
    (try
      (let [pool (database-pool object-store max-open-databases)]
        (connection
          {:borrow-database (fn [database-id]
                              (.borrowObject ^GenericKeyedObjectPool pool database-id))
           :return-database (fn [database-id database]
                              (.returnObject ^GenericKeyedObjectPool pool
                                             database-id
                                             database))
           :close-resources (fn []
                              (close-resources! pool object-store))}))
      (catch Exception error
        (let [cleanup-error (try
                              (ffi/close-object-store! object-store)
                              nil
                              (catch Exception caught
                                caught))]
          (throw (add-suppressed! error cleanup-error)))))))

(defn database
  [{:keys [borrow-database return-database]} {:keys [database-id]}]
  (let [raw-database (borrow-database database-id)
        closed?      (atom false)]
    (->DatabaseLease
      raw-database
      #(when (compare-and-set! closed? false true)
         (return-database database-id raw-database)))))

(defn read-only-snapshot [{:keys [raw-database]}]
  (ffi/await (ffi/open-snapshot raw-database)))

(defn writable-transaction [{:keys [raw-database]}]
  (ffi/await (ffi/begin-transaction raw-database)))

(defn close [{:keys [close-resources]}]
  (close-resources)
  nil)
