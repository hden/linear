(ns linear.adapter.slatedb.connection
  (:require
   [linear.adapter.slatedb.ffi :as ffi])
  (:import
   (java.util.concurrent CompletableFuture)
   (org.apache.commons.pool2 BaseKeyedPooledObjectFactory PooledObject)
   (org.apache.commons.pool2.impl DefaultPooledObject GenericKeyedObjectPool GenericKeyedObjectPoolConfig)))

(defrecord Connection [pool object-store revision-retention-days])

(defn- destroy-database [pooled-database]
  (.join ^CompletableFuture
    (ffi/close-database! (.getObject ^PooledObject pooled-database))))

(defn- database-pool-factory [object-store]
  (proxy [BaseKeyedPooledObjectFactory] []
    (create [database-id]
      ;; Lifecycle ownership must be established before observing interruption.
      (.join ^CompletableFuture (ffi/open-database! object-store database-id)))
    (wrap [database]
      (DefaultPooledObject. database))
    (validateObject [_ pooled-database]
      (ffi/usable-database? (.getObject ^PooledObject pooled-database)))
    (destroyObject
      ([_ pooled-database] (destroy-database pooled-database))
      ([_ pooled-database _mode] (destroy-database pooled-database)))))

(defn open
  {:malli/schema [:->
                  [:map
                   [:object-store-url :string]
                   [:max-open-databases pos-int?]
                   [:revision-retention-days {:optional true} pos-int?]]
                  [:fn #(instance? Connection %)]]}
  [{:keys [object-store-url max-open-databases revision-retention-days]
    :or {revision-retention-days 30}}]
  (let [object-store (ffi/open-object-store object-store-url)
        config (doto (GenericKeyedObjectPoolConfig.)
                 (.setMaxTotal max-open-databases)
                 (.setMaxTotalPerKey 1)
                 (.setMaxIdlePerKey 1)
                 (.setTestOnBorrow true)
                 (.setTestOnReturn true))]
    (->Connection (GenericKeyedObjectPool. (database-pool-factory object-store) config)
                  object-store revision-retention-days)))

(defn ^:no-doc call-with-database [{:keys [pool]} {:keys [database-id f]}]
  (let [database (.borrowObject ^GenericKeyedObjectPool pool database-id)]
    (try
      (when (.isInterrupted (Thread/currentThread))
        (throw (InterruptedException.)))
      (f database)
      (finally
        (.returnObject ^GenericKeyedObjectPool pool database-id database)))))

(defmacro with-database
  [[binding connection options] & body]
  `(call-with-database ~connection
     (assoc ~options :f (fn [~binding] ~@body))))

(defn subscribe [connection {:keys [database-id]}]
  (with-database [database connection {:database-id database-id}]
    (ffi/subscribe database)))

(defn close [{:keys [pool object-store]}]
  (try
    (.close ^GenericKeyedObjectPool pool)
    (finally (ffi/close-object-store! object-store)))
  nil)
