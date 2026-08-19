(ns linear.adapter.pagestore.impl.slatedb.fixture
  (:require
   [clj-ulid :refer [ulid]]
   [linear.adapter.pagestore.impl.slatedb.codec :as codec]
   [linear.adapter.pagestore.impl.slatedb.store :as store]
   [taoensso.tempel :as tempel])
  (:import
   (io.slatedb.uniffi Db DbBuilder DbTransaction IsolationLevel ObjectStore)))

(def keychain (tempel/keychain))

(def encode-revision (codec/encode-revision keychain))
(def encode-page (codec/encode-page keychain))

(defn- write! [^Db database entries]
  (with-open [^DbTransaction transaction
              (deref (.begin database IsolationLevel/SERIALIZABLE_SNAPSHOT))]
    (doseq [[key value] entries]
      (deref (.put transaction key value)))
    (deref (.commit transaction))))

(defn- entries [{:keys [head revisions pages raw-entries]}]
  (concat
    [[(codec/head-key) (codec/encode-head head)]]
    (map (fn [[revision-id revision]]
           (let [key (codec/revision-key revision-id)]
             [key (encode-revision key revision)]))
         revisions)
    (mapcat (fn [[page-id page-versions]]
              (map (fn [[revision-id page]]
                     (let [key (codec/page-key page-id revision-id)]
                       [key (encode-page key page)]))
                   page-versions))
            pages)
    raw-entries))

(defn with-database [snapshot f]
  (with-open [^ObjectStore object-store (ObjectStore/resolve "memory:///")
              ^DbBuilder database-builder (DbBuilder. (str "d-" (ulid)) object-store)
              ^Db database (deref (.build database-builder))]
    (try
      (write! database (entries snapshot))
      (with-open [^DbTransaction transaction
                  (deref (.begin database IsolationLevel/SERIALIZABLE_SNAPSHOT))]
        (f (store/database transaction nil keychain)))
      (finally
        (deref (.shutdown database))))))
