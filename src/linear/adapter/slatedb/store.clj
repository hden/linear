(ns linear.adapter.slatedb.store
  (:require
   [cognitect.anomalies :as anomaly]
   [integrant.core :as integrant]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.snapshot :as snapshot]
   [linear.usecase.database.revisions :as revisions]))

(defn- current-state-records [keychain {:keys [revision head]}]
  (conj (mapv (fn [[page-id page]]
                (let [page-key (key/page page-id)]
                  [page-key (codec/encode-page keychain {:record-key page-key :page page})]))
              (:pages revision))
        [(key/head) (codec/encode-head head)]))

(defn- undo-pages [read-values {:keys [keychain head page-ids]}]
  (let [record-keys (mapv key/page page-ids)
        values (read-values record-keys)]
    (into {}
          (map (fn [page-id record-key value]
                 (let [page (codec/decode-page keychain {:record-key record-key :value value})]
                   (when (and head (<= page-id (:database-page-count head)) (nil? page))
                     (throw (ex-info "Revision does not contain page state"
                                     {::anomaly/category ::anomaly/fault
                                      :reason ::revisions/incomplete-revision
                                      :page-id page-id})))
                   ;; Keep physical tail pages: an older revision can still need them after a regrowth.
                   [page-id page]))
               page-ids record-keys values))))

(defn- add-suppressed! [error {:keys [suppressed]}]
  (when suppressed
    (.addSuppressed ^Exception error ^Exception suppressed))
  error)

(defn- commit-conflict? [error]
  (let [data (ex-data error)]
    (and (= ::anomaly/conflict (::anomaly/category data))
         (= :slatedb (:linear.error/source data))
         (= :commit-transaction (:linear.error/operation data)))))

(defn- revision-conflict [message {:keys [data cause]}]
  (ex-info message
           (merge {::anomaly/category ::anomaly/conflict
                   :reason ::revisions/revision-conflict}
                  data)
           cause))

(defn- ensure-current-parent! [head {:keys [revision]}]
  (when-not (= head (:parent revision))
    (throw (revision-conflict "Database HEAD changed before publish" {:data {:expected-parent (:parent revision)
                                                                             :actual-parent head}
                                                                      :cause nil}))))

(defn- publish! [store {:keys [revision database initialize?]}]
  (let [database-id (:id database)
        keychain    (get-in database [:vault :keychain])]
    (connection/with-database [database-handle store {:database-id database-id}]
      (ffi/with-transaction [transaction database-handle]
        (try
          (let [read-values #(ffi/await
                               (ffi/read-transaction-values transaction %))
                head        (if initialize?
                              (let [[value] (read-values [(key/head)])]
                                (when value
                                  (throw (revision-conflict "Database HEAD already exists" {:data {}
                                                                                            :cause nil})))
                                nil)
                              (snapshot/head-metadata read-values))
                expires-at (max (+ (System/currentTimeMillis)
                                   (* (:revision-retention-days store) 86400000))
                                (or (:history-expires-at head) 0))
                next-head (assoc (select-keys revision [:revision-id :database-page-count])
                                 :history-expires-at expires-at)]
            (ensure-current-parent! (:revision-id head) {:revision revision})
            (let [record-key (key/revision (:revision-id revision))
                  undo {:parent head
                        :undo-pages (undo-pages read-values {:keychain keychain
                                                             :head head
                                                             :page-ids (vec (keys (:pages revision)))})}]
              (ffi/await
                (ffi/write-expiring-values transaction
                                           {:entries [[record-key (codec/encode-revision keychain {:record-key record-key :revision undo})]]
                                            :expires-at expires-at})))
            (ffi/await
              (ffi/write-values transaction
                                (current-state-records keychain {:revision revision
                                                                 :head next-head})))
            (ffi/await (ffi/commit-transaction transaction))
            revision)
          (catch Exception error
            (try
              (ffi/await (ffi/rollback-transaction transaction))
              (catch Exception rollback-error
                (add-suppressed! error {:suppressed rollback-error})))
            (if (commit-conflict? error)
              (throw (revision-conflict "SlateDB publish conflict" {:data {}
                                                                    :cause error}))
              (throw error))))))))

(extend-type linear.adapter.slatedb.connection.Connection
  revisions/Initializable
  (-initialize! [store {:keys [revision database]}]
    (publish! store {:revision revision
                     :database database
                     :initialize? true}))

  revisions/ConsistentReadable
  (-read-consistently [store f database]
    (connection/with-database [database-handle store {:database-id (:id database)}]
      (ffi/with-snapshot [raw-snapshot database-handle]
        (let [read-values #(ffi/await (ffi/read-snapshot-values raw-snapshot %))
              view (snapshot/consistent-view {:read-values read-values :keychain (get-in database [:vault :keychain])})]
          (f view)))))

  revisions/RevisionWritable
  (-publish-next! [store revision database]
    (publish! store {:revision revision
                     :database database
                     :initialize? false}))

  revisions/RevisionAwaitable
  (-await-revision [store {:keys [database-id revision-id timeout-ms]}]
    (ffi/with-change-notifications [await-change (connection/subscribe store {:database-id database-id})]
      (let [current-revision-id
            (connection/with-database [database store {:database-id database-id}]
              (ffi/with-snapshot [raw-snapshot database]
                (:revision-id (snapshot/head-metadata #(ffi/await (ffi/read-snapshot-values raw-snapshot %))))))]
        (when (= current-revision-id revision-id)
          (await-change {:timeout-ms timeout-ms}))))))

(defmethod integrant/init-key :linear.adapter.slatedb.store/store
  [_ options]
  (connection/open options))

(defmethod integrant/halt-key! :linear.adapter.slatedb.store/store
  [_ store]
  (connection/close store))
