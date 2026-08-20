(ns linear.adapter.pagestore.impl.slatedb.connection
  (:require
   [integrant.core :as integrant]
   [linear.adapter.pagestore.core :as pagestore]
   [linear.adapter.pagestore.impl.slatedb.store :as store])
  (:import
   (io.slatedb.uniffi Admin AdminBuilder CloneBuilder CloneSourceSpec Db DbBuilder DbTransaction FlushOptions FlushType IsolationLevel ObjectStore)
   (org.apache.commons.pool2 BaseKeyedPooledObjectFactory PooledObject)
   (org.apache.commons.pool2.impl DefaultPooledObject GenericKeyedObjectPool GenericKeyedObjectPoolConfig)))

(defn- close-database! [^Db database]
  (try
    (deref (.shutdown database))
    (finally
      (.close database))))

(defn- database-pool-factory [^ObjectStore object-store]
  (proxy [BaseKeyedPooledObjectFactory] []
    (create [^String database-name]
      (with-open [database-builder (DbBuilder. database-name object-store)]
        (deref (.build database-builder))))
    (wrap [^Db database]
      (DefaultPooledObject. database))
    (destroyObject [_ ^PooledObject pooled-database]
      (close-database! (.getObject pooled-database)))))

(defn- database-pool [^ObjectStore object-store {:keys [max-open-databases]}]
  (let [pool-config (GenericKeyedObjectPoolConfig.)]
    (when max-open-databases
      (.setMaxTotal pool-config max-open-databases))
    (.setMaxTotalPerKey pool-config 1)
    (.setMaxIdlePerKey pool-config 1)
    (GenericKeyedObjectPool. (database-pool-factory object-store) pool-config)))

(defrecord SlateDbConnection [^GenericKeyedObjectPool database-pool ^ObjectStore object-store]
  pagestore/Connection
  (-create-db! [_ {:keys [db from]}]
    (if from
      (let [^String source from
            ^Db source-database (.borrowObject database-pool source)]
        (try
          (deref (.flushWithOptions source-database (FlushOptions. FlushType/MEM_TABLE)))
          (with-open [^AdminBuilder admin-builder (AdminBuilder. source object-store)
                      ^Admin admin (.build admin-builder)
                      ^CloneBuilder clone-builder (.createCloneBuilderFromSource
                                                    admin
                                                    (CloneSourceSpec. source nil nil))]
            (.withClonePath clone-builder db)
            (deref (.build clone-builder)))
            ;; TODO: For PITR, override head & delete unwanted revisions
          (finally
            (.returnObject database-pool source source-database))))
      (let [^Db database (.borrowObject database-pool db)]
        (.returnObject database-pool db database))))

  (-execute! [_ database-id f {:keys [isolation keychain]
                               :or {isolation IsolationLevel/SERIALIZABLE_SNAPSHOT}}]
    (let [^Db database (.borrowObject database-pool database-id)]
      (try
        (with-open [^DbTransaction transaction (deref (.begin database isolation))]
          (let [result (f (store/database transaction nil keychain))]
            ;; TODO: ingest new revision
            ;; TODO: catch & rollback
            (deref (.commit transaction))
            ;; TODO: automatic retry for serialization errors
            result))
        (finally
          (.returnObject database-pool database-id database))))))

(defmethod integrant/init-key :linear.adapter.pagestore.impl.slatedb/connection [_ options]
  (let [object-store (ObjectStore/resolve (:object-store-url options))]
    (->SlateDbConnection (database-pool object-store options) object-store)))

(defmethod integrant/halt-key! :linear.adapter.pagestore.impl.slatedb/connection [_ connection]
  (try
    (.close ^GenericKeyedObjectPool (:database-pool connection))
    (finally
      (.close ^ObjectStore (:object-store connection)))))
