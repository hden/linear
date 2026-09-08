(ns linear.adapter.sqlite.file-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.sqlite.file :as file]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.usecase.database.revisions :as revisions]))

(defrecord Snapshot [pages]
  revisions/Snapshot
  (-revision-id [_] "r-0")
  (-size [_] (count pages))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn- page [fill]
  (doto (byte-array (repeat 512 (byte fill)))
    (aset 16 (byte 2))
    (aset 17 (byte 0))))

(defn- large-page []
  (doto (byte-array 65536)
    (aset 16 (byte 0))
    (aset 17 (byte 1))))

(deftest reads-across-snapshot-pages
  (let [snapshot (->Snapshot {1 (page 1)
                              2 (page 2)})
        result   (sqlite/read (file/snapshot-file snapshot) 510 4)]
    (is (= [1 1 2 2] (mapv #(bit-and % 0xff) (:bytes result))))
    (is (false? (:short-read? result)))))

(deftest zero-fills-a-short-read
  (let [snapshot (->Snapshot {1 (page 1)})
        result   (sqlite/read (file/snapshot-file snapshot) 510 4)]
    (is (= [1 1 0 0] (mapv #(bit-and % 0xff) (:bytes result))))
    (is (true? (:short-read? result)))))

(deftest snapshot-file-is-immutable
  (let [snapshot-file (file/snapshot-file (->Snapshot {1 (page 1)}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sqlite/write snapshot-file 0 (byte-array 1))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sqlite/truncate snapshot-file 0)))))

(deftest recognizes-the-64-kib-page-size-sentinel
  (is (= 65536
         (sqlite/size (file/snapshot-file (->Snapshot {1 (large-page)}))))))

(deftest reads-page-size-once-when-constructing-the-file
  (let [fetches (atom 0)
        snapshot (reify revisions/Snapshot
                   (-revision-id [_] "r-0")
                   (-size [_] 1)
                   (-fetch-pages-by-ids [_ _]
                     (swap! fetches inc)
                     {1 (page 1)}))
        snapshot-file (file/snapshot-file snapshot)]
    (sqlite/size snapshot-file)
    (sqlite/size snapshot-file)
    (is (= 1 @fetches))))
