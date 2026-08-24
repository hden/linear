(ns linear.adapter.sqlite.wal-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.wal :as wal]))

(def ^:private page-size 4096)

(defn- put-u32! [^bytes bytes offset value]
  (aset-byte bytes offset (unchecked-byte (bit-shift-right value 24)))
  (aset-byte bytes (inc offset) (unchecked-byte (bit-shift-right value 16)))
  (aset-byte bytes (+ offset 2) (unchecked-byte (bit-shift-right value 8)))
  (aset-byte bytes (+ offset 3) (unchecked-byte value))
  bytes)

(defn- wal-header []
  (doto (byte-array 32)
    (put-u32! 0 0x377f0682)
    (put-u32! 8 page-size)))

(defn- frame [page-number page-count-after-commit page]
  (let [bytes (byte-array (+ 24 page-size))]
    (put-u32! bytes 0 page-number)
    (put-u32! bytes 4 page-count-after-commit)
    (System/arraycopy page 0 bytes 24 page-size)
    bytes))

(deftest commit-returns-the-last-image-for-a-committed-page
  (let [page (byte-array page-size)
        capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (wal-header)})
                    (wal/write {:offset 32 :bytes (frame 2 2 page)}))
        delta (wal/commit capture)]
    (is (= 2 (:database-page-count delta)))
    (is (java.util.Arrays/equals page (get-in delta [:pages 2])))))

(deftest incomplete-frame-does-not-commit
  (let [capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (wal-header)})
                    (wal/write {:offset 32 :bytes (byte-array 24)}))]
    (is (nil? (wal/commit capture)))))

(deftest sync-does-not-republish-an-earlier-commit
  (let [first-page (byte-array page-size)
        next-page (byte-array page-size)
        capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (wal-header)})
                    (wal/write {:offset 32 :bytes (frame 2 2 first-page)}))
        [capture committed] (wal/sync capture)
        capture (wal/write capture
                           {:offset (+ 32 24 page-size)
                            :bytes (frame 3 0 next-page)})]
    (is (= 2 (:database-page-count committed)))
    (is (nil? (wal/commit capture)))))

(deftest overwriting-a-parsed-frame-rebuilds-the-candidate
  (let [old-page (byte-array page-size)
        new-page (byte-array (repeat page-size 1))
        capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (wal-header)})
                    (wal/write {:offset 32 :bytes (frame 2 2 old-page)})
                    (wal/write {:offset 32 :bytes (frame 3 3 new-page)}))
        candidate (wal/commit capture)]
    (is (= 3 (:database-page-count candidate)))
    (is (= #{3} (set (keys (:pages candidate)))))
    (is (java.util.Arrays/equals new-page (get-in candidate [:pages 3])))))

(deftest overwriting-a-synced-frame-starts-a-new-wal-generation
  (let [old-page (byte-array page-size)
        new-page (byte-array (repeat page-size 1))
        capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (wal-header)})
                    (wal/write {:offset 32 :bytes (frame 2 2 old-page)}))
        [capture _committed] (wal/sync capture)
        candidate (-> capture
                      (wal/write {:offset 32 :bytes (frame 3 3 new-page)})
                      wal/commit)]
    (is (= 3 (:database-page-count candidate)))
    (is (= #{3} (set (keys (:pages candidate)))))))

(deftest truncate-removes-bytes-after-the-new-length
  (let [capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (byte-array [1 2 3 4])})
                    (wal/truncate 2))]
    (is (= 2 (wal/size capture)))
    (is (= [1 2] (vec (:bytes (wal/read capture 0 2)))))
    (is (:short-read? (wal/read capture 0 3)))))

(deftest invalid-header-is-reported-as-a-fault
  (let [error (try
                (wal/write (wal/new-capture)
                           {:offset 0 :bytes (byte-array 32)})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))))

(deftest overlapping-writes-are-read-as-one-byte-range
  (let [capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (byte-array [1 2 3])})
                    (wal/write {:offset 1 :bytes (byte-array [9 8])}))]
    (testing "the newest write wins"
      (is (= [1 9 8]
             (vec (:bytes (wal/read capture 0 3))))))))
