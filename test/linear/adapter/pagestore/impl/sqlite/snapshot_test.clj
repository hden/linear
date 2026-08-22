(ns linear.adapter.pagestore.impl.sqlite.snapshot-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.adapter.pagestore.impl.core :as core]
   [linear.adapter.pagestore.impl.sqlite.snapshot :as snapshot]))

(defrecord FakeDatabase [page-count pages]
  core/Database
  (-revision-id [_]
    "r0")
  (-size [_]
    page-count)
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn- bytes= [expected actual]
  (java.util.Arrays/equals ^bytes expected ^bytes actual))

(deftest page-ids-are-derived-from-logical-page-numbers
  (testing "page numbers are translated at the pagestore boundary"
    (is (= "1" (snapshot/page-id 1)))
    (is (= "42" (snapshot/page-id 42)))))

(deftest database-pages-are-fetched-by-logical-page-number
  (let [page-1 (byte-array [1 2])
        page-2 (byte-array [3 4])
        database (->FakeDatabase 2 {"1" page-1 "2" page-2})]
    (testing "the SQLite integration does not expose storage ids to its caller"
      (is (= 2 (snapshot/page-count database)))
      (let [pages (snapshot/fetch-pages database #{1 2})]
        (is (bytes= page-1 (get pages 1)))
        (is (bytes= page-2 (get pages 2)))))))
