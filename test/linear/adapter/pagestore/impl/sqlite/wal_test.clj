(ns linear.adapter.pagestore.impl.sqlite.wal-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.impl.sqlite.wal :as wal]
   [linear.test :refer [catch-ex-data]])
  (:import
   (java.util Arrays)))

(def ^:private page-size 4096)

(defn- put-u32! [^bytes bytes offset value]
  (aset-byte bytes offset (unchecked-byte (bit-and (bit-shift-right value 24) 0xff)))
  (aset-byte bytes (inc offset) (unchecked-byte (bit-and (bit-shift-right value 16) 0xff)))
  (aset-byte bytes (+ offset 2) (unchecked-byte (bit-and (bit-shift-right value 8) 0xff)))
  (aset-byte bytes (+ offset 3) (unchecked-byte (bit-and value 0xff)))
  bytes)

(defn- wal-header []
  (doto (byte-array 32)
    (put-u32! 0 0x377f0682)
    (put-u32! 4 3007000)
    (put-u32! 8 page-size)))

(defn- frame-header [page-number page-count-after-commit]
  (doto (byte-array 24)
    (put-u32! 0 page-number)
    (put-u32! 4 page-count-after-commit)))

(deftest sync-emits-a-committed-page-image
  (let [page (byte-array page-size)
        _    (aset-byte page 0 (byte 42))
        [_ delta]
        (-> (wal/new-capture)
            (wal/write {:offset 0 :bytes (wal-header)})
            (wal/write {:offset 32 :bytes (frame-header 2 2)})
            (wal/write {:offset 56 :bytes page})
            wal/sync)]
    (is (= 2 (:database-page-count delta)))
    (is (Arrays/equals page (get-in delta [:pages 2])))))

(deftest commit-candidate-needs-a-successful-sync
  (let [page (byte-array page-size)
        capture
        (-> (wal/new-capture)
            (wal/write {:offset 0 :bytes (wal-header)})
            (wal/write {:offset 32 :bytes (frame-header 2 2)})
            (wal/write {:offset 56 :bytes page}))]
    (is (= 2 (get-in capture [:candidate-delta :database-page-count])))
    (let [[_ delta] (wal/sync capture)]
      (is (= 2 (:database-page-count delta))))))

(deftest last-page-image-wins-within-a-transaction
  (let [first-page  (byte-array page-size)
        second-page (byte-array page-size)
        _           (aset-byte first-page 0 (byte 1))
        _           (aset-byte second-page 0 (byte 2))
        frame-size  (+ 24 page-size)
        [_ delta]
        (-> (wal/new-capture)
            (wal/write {:offset 0 :bytes (wal-header)})
            (wal/write {:offset 32 :bytes (frame-header 2 0)})
            (wal/write {:offset 56 :bytes first-page})
            (wal/write {:offset (+ 32 frame-size) :bytes (frame-header 2 2)})
            (wal/write {:offset (+ 56 frame-size) :bytes second-page})
            wal/sync)]
    (is (Arrays/equals second-page (get-in delta [:pages 2])))))

(deftest truncate-to-zero-discards-an-uncommitted-transaction
  (let [page    (byte-array page-size)
        capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (wal-header)})
                    (wal/write {:offset 32 :bytes (frame-header 2 2)})
                    (wal/write {:offset 56 :bytes page})
                    (wal/truncate 0))
        [_ delta] (wal/sync capture)]
    (is (nil? delta))))

(deftest invalid-wal-header-is-a-fault-anomaly
  (let [error (catch-ex-data #(wal/write (wal/new-capture)
                                {:offset 0 :bytes (byte-array 32)}))]
    (is (= ::anomaly/fault (::anomaly/category error)))))

(deftest raw-wal-reads-observe-the-latest-overlapping-write
  (let [capture (-> (wal/new-capture)
                    (wal/write {:offset 0 :bytes (byte-array [1 2])})
                    (wal/write {:offset 1 :bytes (byte-array [9])}))
        result  (wal/read-at capture 0 2)]
    (is (= 2 (wal/size capture)))
    (is (false? (:short-read? result)))
    (is (Arrays/equals (byte-array [1 9]) (:bytes result)))))
