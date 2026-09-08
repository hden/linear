(ns linear.adapter.slatedb.store
  (:require
   [cognitect.anomalies :as anomaly]
   [integrant.core :as integrant]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.snapshot :as snapshot]
   [linear.usecase.database.revisions :as revisions])
  (:import
   (io.slatedb.uniffi DbTransaction)
   (java.lang AutoCloseable)))

(defn- revision-records [keychain revision]
  (let [revision-id  (:revision-id revision)
        revision-key (key/revision revision-id)]
    (into [[revision-key
            (codec/encode-revision keychain revision-key revision)]]
          (concat
            (map (fn [[page-id page]]
                   (let [page-key (key/page page-id)]
                     [page-key (codec/encode-page keychain page-key page)]))
                 (:pages revision))
            [[(key/head) (codec/encode-head {:revision-id revision-id})]]))))

(defn- add-suppressed! [error suppressed]
  (when suppressed
    (.addSuppressed ^Exception error ^Exception suppressed))
  error)

(defn- commit-conflict? [error]
  (let [data (ex-data error)]
    (and (= ::anomaly/conflict (::anomaly/category data))
         (= :slatedb (:linear.error/source data))
         (= :commit-transaction (:linear.error/operation data)))))

(defn- revision-conflict [message data cause]
  (ex-info message
           (merge {::anomaly/category ::anomaly/conflict
                   :reason ::revisions/revision-conflict}
                  data)
           cause))

(defn- ensure-current-parent! [head revision]
  (when-not (= head (:parent revision))
    (throw (revision-conflict
             "Database HEAD changed before publish"
             {:expected-parent (:parent revision)
              :actual-parent head}
             nil))))

(defn- publish! [store revision database]
  (let [database-id (:id database)
        keychain    (get-in database [:vault :keychain])]
    (with-open [^AutoCloseable leased-database
                (connection/database store database-id)
                ^DbTransaction transaction
                (connection/writable-transaction leased-database)]
      (try
        (let [read-values #(ffi/await
                             (ffi/read-transaction-values transaction %))
              head        (snapshot/head-revision-id read-values)]
          (ensure-current-parent! head revision)
          (ffi/await
            (ffi/write-values transaction
                              (revision-records keychain revision)))
          (ffi/await (ffi/commit-transaction transaction))
          revision)
        (catch Exception error
          (try
            (ffi/await (ffi/rollback-transaction transaction))
            (catch Exception rollback-error
              (add-suppressed! error rollback-error)))
          (if (commit-conflict? error)
            (throw (revision-conflict "SlateDB publish conflict" {} error))
            (throw error)))))))

(defn- close-snapshot-resources! [raw-snapshot leased-database]
  (let [snapshot-error (try
                         (ffi/close-snapshot! raw-snapshot)
                         nil
                         (catch Exception error
                           error))
        database-error (try
                         (.close ^AutoCloseable leased-database)
                         nil
                         (catch Exception error
                           error))]
    (cond
      snapshot-error (throw (add-suppressed! snapshot-error database-error))
      database-error (throw database-error))))

(defn- with-consistent-view [store database f]
  (let [database-id      (:id database)
        keychain         (get-in database [:vault :keychain])
        leased-database (connection/database store database-id)
        raw-snapshot    (try
                          (connection/read-only-snapshot leased-database)
                          (catch Exception error
                            (try
                              (.close ^AutoCloseable leased-database)
                              (catch Exception close-error
                                (add-suppressed! error close-error)))
                            (throw error)))
        read-values      #(ffi/await
                            (ffi/read-snapshot-values raw-snapshot %))
        view             (snapshot/consistent-view read-values keychain)
        outcome          (try
                           {:value (f view)}
                           (catch Exception error
                             {:error error}))]
    (try
      (close-snapshot-resources! raw-snapshot leased-database)
      (catch Exception close-error
        (if-let [error (:error outcome)]
          (throw (add-suppressed! error close-error))
          (throw close-error))))
    (if-let [error (:error outcome)]
      (throw error)
      (:value outcome))))

(extend-type linear.adapter.slatedb.connection.Connection
  revisions/ConsistentReadable
  (-read-consistently [store f database]
    (with-consistent-view store database f))

  revisions/RevisionWritable
  (-publish-next! [store revision database]
    (publish! store revision database)))

(defmethod integrant/init-key :linear.adapter.slatedb.store/store
  [_ options]
  (connection/open options))

(defmethod integrant/halt-key! :linear.adapter.slatedb.store/store
  [_ store]
  (connection/close store))
