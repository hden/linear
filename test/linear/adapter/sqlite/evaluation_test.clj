(ns linear.adapter.sqlite.evaluation-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.evaluation :as evaluation]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.usecase.database.revisions :as revisions]))

(defrecord Snapshot [pages]
  revisions/Snapshot
  (-revision-id [_] "r-0")
  (-size [_] (count pages))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn- page []
  (doto (byte-array 512)
    (aset 16 (byte 2))
    (aset 17 (byte 0))))

(defn- put-u32! [^bytes bytes offset value]
  (aset-byte bytes offset (unchecked-byte (bit-shift-right value 24)))
  (aset-byte bytes (inc offset) (unchecked-byte (bit-shift-right value 16)))
  (aset-byte bytes (+ offset 2) (unchecked-byte (bit-shift-right value 8)))
  (aset-byte bytes (+ offset 3) (unchecked-byte value))
  bytes)

(defn- wal-header []
  (doto (byte-array 32)
    (put-u32! 0 0x377f0682)
    (put-u32! 8 512)))

(defn- commit-frame [page-number database-page-count]
  (doto (byte-array (+ 24 512))
    (put-u32! 0 page-number)
    (put-u32! 4 database-page-count)))

(def ^:private snapshot (->Snapshot {1 (page)}))

(defn- open-file [filesystem path mode kind]
  (:file (sqlite/open filesystem
                      {:path path
                       :requested-mode mode
                       :kind kind
                       :options #{}})))

(defn- accessible? [filesystem path]
  (sqlite/access filesystem {:path path :mode :exists}))

(deftest routes-main-and-wal-files-without-file-identifiers
  (let [filesystem (evaluation/evaluation snapshot "/linear/eval.db")
        main       (open-file filesystem "/linear/eval.db" :read-only :main-db)
        wal        (open-file filesystem "/linear/eval.db-wal" :read-write :wal)]
    (is (identical? main (open-file filesystem "/linear/eval.db" :read-only :main-db)))
    (is (identical? wal (open-file filesystem "/linear/eval.db-wal" :read-write :wal)))
    (is (= 512 (sqlite/size main)))
    (is (zero? (sqlite/size wal)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (open-file filesystem "/linear/eval.db-shm" :read-write :transient-db)))))

(deftest access-and-delete-reflect-wal-state
  (let [filesystem (evaluation/evaluation snapshot "/linear/eval.db")
        wal        (open-file filesystem "/linear/eval.db-wal" :read-write :wal)]
    (is (true? (accessible? filesystem "/linear/eval.db")))
    (is (false? (accessible? filesystem "/linear/eval.db-wal")))
    (sqlite/write wal 0 (byte-array 8))
    (is (true? (accessible? filesystem "/linear/eval.db-wal")))
    (sqlite/delete filesystem {:path "/linear/eval.db-wal"})
    (is (false? (accessible? filesystem "/linear/eval.db-wal")))
    (is (= "/linear/eval.db"
           (sqlite/full-path filesystem {:path "/linear/eval.db"})))))

(deftest requires-exactly-one-commit
  (let [filesystem (evaluation/evaluation snapshot "/linear/eval.db")
        missing    (try
                     (evaluation/commit filesystem)
                     nil
                     (catch clojure.lang.ExceptionInfo exception
                       exception))]
    (is (= ::anomaly/incorrect (-> missing ex-data ::anomaly/category)))
    (is (= ::evaluation/missing-commit (:reason (ex-data missing)))))
  (let [filesystem (evaluation/evaluation snapshot "/linear/eval.db")
        wal-file   (open-file filesystem "/linear/eval.db-wal" :read-write :wal)]
    (sqlite/write wal-file 0 (wal-header))
    (sqlite/write wal-file 32 (commit-frame 1 1))
    (sqlite/sync wal-file)
    (sqlite/write wal-file (+ 32 24 512) (commit-frame 2 2))
    (sqlite/sync wal-file)
    (let [multiple (try
                     (evaluation/commit filesystem)
                     nil
                     (catch clojure.lang.ExceptionInfo exception
                       exception))]
      (is (= ::anomaly/incorrect (-> multiple ex-data ::anomaly/category)))
      (is (= ::evaluation/multiple-commits (:reason (ex-data multiple)))))))
