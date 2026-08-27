(ns linear.adapter.slatedb.snapshot
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.key :as key]
   [linear.usecase.database :as database])
  (:import
   (java.lang AutoCloseable)))

(defn head-revision-id
  {:malli/schema [:-> [:fn ifn?] ::database/revision-id]}
  [read-values]
  (let [[value] (read-values [(key/head)])
        head (codec/decode-head value)]
    (or (:revision-id head)
        (throw (ex-info "SlateDB HEAD does not exist"
                        {::anomaly/category ::anomaly/not-found
                         :reason ::database/head-not-found})))))

(defn- revision-metadata [read-values keychain revision-id]
  (let [record-key (key/revision revision-id)
        [value] (read-values [record-key])]
    (codec/decode-revision keychain record-key value)))

(defn- current-pages [read-values keychain page-ids]
  (let [page-ids (vec page-ids)
        record-keys (mapv key/page page-ids)
        values (read-values record-keys)]
    (into {}
          (map (fn [page-id record-key value]
                 [page-id (codec/decode-page keychain record-key value)])
               page-ids
               record-keys
               values))))

(defrecord ^:private Snapshot [read-values revision-id keychain close! closed?]
  database/Snapshot
  (-revision-id [_]
    revision-id)
  (-size [_]
    (:database-page-count
      (revision-metadata read-values keychain revision-id)))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (current-pages read-values keychain ids))
  AutoCloseable
  (close [_]
    (when (compare-and-set! closed? false true)
      (close!))))

(defn snapshot
  {:malli/schema [:->
                  [:fn ifn?]
                  ::database/revision-id
                  ::database/keychain
                  [:fn ifn?]
                  [:and
                   ::database/snapshot
                   [:fn #(instance? AutoCloseable %)]]]}
  [read-values revision-id keychain close!]
  (->Snapshot read-values revision-id keychain close! (atom false)))
