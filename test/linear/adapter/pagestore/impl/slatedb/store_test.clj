(ns linear.adapter.pagestore.impl.slatedb.store-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.pagestore.impl.core :as core]
   [linear.adapter.pagestore.impl.slatedb.fixture :as fixture]))

(deftest database-resolves-the-latest-page-at-or-before-the-snapshot
  (fixture/with-database
    {:head {:revision-id "r2"}
     :revisions {"r2" {:id                  "r2"
                       :parent              "r1"
                       :database-page-count 2}}
     :pages {"1" {"r1" (byte-array [1])
                  "r2" (byte-array [2])
                  "r3" (byte-array [3])}
             "2" {"r1" (byte-array [9])}}}
    (fn [database]
      (is (= "r2" (core/revision-id database)))
      (is (= 2 (core/size database)))
      (let [pages (core/fetch-pages-by-ids database {:ids #{"1" "2" "3"}})]
        (is (java.util.Arrays/equals (byte-array [2]) (get pages "1")))
        (is (java.util.Arrays/equals (byte-array [9]) (get pages "2")))
        (is (nil? (get pages "3")))))))
