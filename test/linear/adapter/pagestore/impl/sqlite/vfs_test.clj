(ns linear.adapter.pagestore.impl.sqlite.vfs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.adapter.pagestore.impl.core :as core]
   [linear.adapter.pagestore.impl.sqlite.vfs :as vfs]))

(defrecord FakeDatabase [page-count pages]
  core/Database
  (-revision-id [_]
    "r0")
  (-size [_]
    page-count)
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn- page [page-number]
  (let [page (byte-array 1024)]
    (dotimes [index 1024]
      (aset-byte page index (byte page-number)))
    (when (= page-number 1)
      (aset-byte page 16 (byte 4))
      (aset-byte page 17 (byte 0)))
    page))

(defn- bytes= [expected actual]
  (java.util.Arrays/equals ^bytes expected ^bytes actual))

(deftest main-database-size-uses-its-sqlite-page-size
  (let [database (->FakeDatabase 2 {"1" (page 1) "2" (page 2)})]
    (is (= 2048 (vfs/main-database-size database)))))

(deftest main-database-read-crosses-logical-pages-without-a-storage-range
  (let [page-1   (page 1)
        page-2   (page 2)
        database (->FakeDatabase 2 {"1" page-1 "2" page-2})
        result   (vfs/read-main-database database 1020 8)]
    (testing "the SQLite byte range is assembled from logical pages"
      (is (false? (:short-read? result)))
      (is (bytes= (byte-array [1 1 1 1 2 2 2 2]) (:bytes result))))))

(deftest main-database-read-zero-fills-past-end-of-file
  (let [database (->FakeDatabase 2 {"1" (page 1) "2" (page 2)})
        result   (vfs/read-main-database database 2046 8)]
    (is (:short-read? result))
    (is (bytes= (byte-array [2 2 0 0 0 0 0 0]) (:bytes result)))))
