(ns linear.adapter.slatedb.snapshot-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.snapshot :as snapshot]
   [linear.usecase.database :as database]
   [taoensso.tempel :as tempel])
  (:import
   (java.lang AutoCloseable)
   (java.util Arrays UUID)))

(defn- revision-records [keychain]
  (let [revision {:revision-id         "r-01K002"
                  :parent              "r-01K001"
                  :database-page-count 2
                  :pages               {1 (byte-array [2])
                                        2 (byte-array [9])}}
        revision-key (key/revision (:revision-id revision))]
    [[(key/head) (codec/encode-head {:revision-id (:revision-id revision)})]
     [revision-key (codec/encode-revision keychain revision-key revision)]
     [(key/page 1) (codec/encode-page keychain (key/page 1) (get-in revision [:pages 1]))]
     [(key/page 2) (codec/encode-page keychain (key/page 2) (get-in revision [:pages 2]))]]))

(defn- with-seeded-snapshot [f]
  (let [keychain (tempel/keychain)
        object-store (ffi/open-object-store "memory:///")
        database (ffi/open-database! object-store (str "d-" (UUID/randomUUID)))]
    (try
      (let [transaction (ffi/await (ffi/begin-transaction database))]
        (try
          (ffi/await (ffi/write-values transaction (revision-records keychain)))
          (ffi/await (ffi/commit-transaction transaction))
          (finally
            (ffi/close-transaction! transaction))))
      (let [snapshot (ffi/await (ffi/open-snapshot database))]
        (try
          (f snapshot keychain)
          (finally
            (ffi/close-snapshot! snapshot))))
      (finally
        (ffi/close-database! database)
        (ffi/close-object-store! object-store)))))

(deftest snapshot-reads-its-revision-metadata-and-pages
  (with-seeded-snapshot
    (fn [raw-snapshot keychain]
      (let [read-values #(ffi/await (ffi/read-snapshot-values raw-snapshot %))
            database-snapshot (snapshot/snapshot read-values
                                                 "r-01K002"
                                                 keychain
                                                 (fn []))]
        (with-open [database-snapshot database-snapshot]
          (let [pages (database/fetch-pages-by-ids database-snapshot
                        {:ids #{1 2 3}})]
            (is (= "r-01K002" (database/revision-id database-snapshot)))
            (is (= 2 (database/size database-snapshot)))
            (is (Arrays/equals (byte-array [2]) (get pages 1)))
            (is (Arrays/equals (byte-array [9]) (get pages 2)))
            (is (nil? (get pages 3)))))))))

(deftest reads-head-revision-id
  (with-seeded-snapshot
    (fn [raw-snapshot _keychain]
      (let [read-values #(ffi/await (ffi/read-snapshot-values raw-snapshot %))]
        (is (= "r-01K002" (snapshot/head-revision-id read-values)))))))

(deftest snapshot-invokes-close-callback-only-once
  (let [close-count       (atom 0)
        database-snapshot (snapshot/snapshot (constantly [])
                                             "r-01K002"
                                             ::keychain
                                             #(swap! close-count inc))]
    (.close ^AutoCloseable database-snapshot)
    (.close ^AutoCloseable database-snapshot)
    (is (= 1 @close-count))))
