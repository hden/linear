(ns linear.adapter.pagestore.impl.slatedb.store
  (:require
   [linear.adapter.pagestore.impl.core :as core]
   [linear.adapter.pagestore.impl.slatedb.codec :as codec]
   [promesa.core :as promesa])
  (:import
   (io.slatedb.uniffi DbIterator DbTransaction KeyRange KeyValue)))

(defprotocol SnapshotStore
  (-head [store])
  (-revision [store revision-id])
  (-pages [store revision-id page-ids]))

(defn- get-value [^DbTransaction transaction key decode]
  (promesa/then (.get transaction key) decode))

(defn- await! [promise]
  (let [result (promesa/await promise)]
    (if (instance? Throwable result)
      (throw (or (.getCause ^Throwable result) result))
      result)))

(defn- read-page-versions [^DbIterator iterator prefix as-of-revision-id keychain latest-page]
  (let [decode-page (codec/decode-page keychain)]
    (promesa/loop [latest-page latest-page]
      (promesa/let [^KeyValue entry (.next iterator)]
        (if entry
          (let [entry-key (.key entry)
                entry-revision-id (codec/page-revision-id prefix entry-key)
                at-or-before? (not (pos? (compare entry-revision-id as-of-revision-id)))]
            (promesa/recur (if at-or-before?
                             (decode-page entry-key (.value entry))
                             latest-page)))
          latest-page)))))

(defn- latest-page-version [^DbTransaction transaction {:keys [page-id
                                                               keychain
                                                               as-of-revision-id]}]
  (let [prefix (codec/page-key-prefix page-id)]
    (promesa/then
      (.scanPrefix transaction prefix (KeyRange. nil false nil false))
      (fn [^DbIterator iterator]
        (promesa/finally
          (read-page-versions iterator prefix as-of-revision-id keychain nil)
          (fn [_ _]
            (.close iterator)))))))

(defn- as-of-revision-id [store revision-id]
  (if revision-id
    (promesa/resolved revision-id)
    (promesa/then (-head store) :revision-id)))

(defrecord SlateDbSnapshotStore [^DbTransaction transaction revision-id keychain]
  SnapshotStore
  (-head [_]
    (get-value transaction (codec/head-key) codec/decode-head))
  (-revision [_ id]
    (let [key (codec/revision-key id)]
      (get-value transaction key (partial (codec/decode-revision keychain) key))))
  (-pages [_ as-of-id page-ids]
    (promesa/then
      (promesa/all
        (map (fn [page-id]
               (promesa/then (latest-page-version transaction {:page-id page-id
                                                               :as-of-revision-id as-of-id
                                                               :keychain keychain})
                             (fn [page]
                               [page-id page])))
             page-ids))
      (partial into {})))

  core/Database
  (-revision-id [this]
    (await! (as-of-revision-id this revision-id)))
  (-size [this]
    (await!
      (promesa/then (as-of-revision-id this revision-id)
                    (fn [as-of-id]
                      (promesa/then (-revision this as-of-id)
                                    :database-page-count)))))
  (-fetch-pages-by-ids [this {:keys [ids]}]
    (await!
      (promesa/then (as-of-revision-id this revision-id)
                    (fn [as-of-id]
                      (-pages this as-of-id ids))))))

(defn database [transaction revision-id keychain]
  (->SlateDbSnapshotStore transaction revision-id keychain))
