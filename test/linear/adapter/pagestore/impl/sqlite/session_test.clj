(ns linear.adapter.pagestore.impl.sqlite.session-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.impl.sqlite.session :as session]
   [linear.test :refer [catch-ex-data]]))

(def ^:private page-size 512)

(defn- put-u32! [^bytes bytes offset value]
  (aset-byte bytes offset (unchecked-byte (bit-and (bit-shift-right value 24) 0xff)))
  (aset-byte bytes (inc offset) (unchecked-byte (bit-and (bit-shift-right value 16) 0xff)))
  (aset-byte bytes (+ offset 2) (unchecked-byte (bit-and (bit-shift-right value 8) 0xff)))
  (aset-byte bytes (+ offset 3) (unchecked-byte (bit-and value 0xff)))
  bytes)

(defn- wal-header []
  (doto (byte-array 32)
    (put-u32! 0 0x377f0682)
    (put-u32! 8 page-size)))

(defn- frame-header [page-number page-count-after-commit]
  (doto (byte-array 24)
    (put-u32! 0 page-number)
    (put-u32! 4 page-count-after-commit)))

(deftest wal-sync-retains-a-canonical-delta-after-the-raw-wal-is-reset
  (let [database ::database
        session  (session/new-session database)
        page     (byte-array page-size)]
    (session/write-wal! session 0 (wal-header))
    (session/write-wal! session 32 (frame-header 1 1))
    (session/write-wal! session 56 page)
    (is (= 1 (:database-page-count (session/sync-wal! session))))
    (session/truncate-wal! session 0)
    (is (= database (session/database session)))
    (is (zero? (session/wal-size session)))
    (is (= 1 (count (session/committed-deltas session))))
    (is (= 1 (:database-page-count (session/canonical-revision session))))
    (is (contains? (:pages (session/canonical-revision session)) 1))))

(deftest canonical-revision-rejects-multiple-sqlite-commits
  (let [sqlite-session (session/new-session ::database)
        delta          {:database-page-count 1 :pages {1 (byte-array page-size)}}
        error          (do
                         (swap! (:committed-deltas sqlite-session) conj delta delta)
                         (catch-ex-data #(session/canonical-revision sqlite-session)))]
    (is (= ::anomaly/incorrect (::anomaly/category error)))
    (is (= ::session/multiple-wal-commits (:reason error)))
    (is (= 2 (:count error)))))

(deftest throw-if-failed-rethrows-a-virtual-machine-error-from-a-callback
  (let [sqlite-session (session/new-session ::database)
        cause          (OutOfMemoryError. "native callback allocation failed")]
    (reset! (session/failure-slot sqlite-session) cause)
    (let [error (try
                  (session/throw-if-failed! sqlite-session)
                  nil
                  (catch Throwable error
                    error))]
      (is (identical? cause error)))))
