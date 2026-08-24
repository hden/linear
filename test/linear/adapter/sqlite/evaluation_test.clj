(ns linear.adapter.sqlite.evaluation-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.evaluation :as evaluation]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.adapter.sqlite.wal :as wal]
   [linear.protocol :as protocol]))

(defrecord Snapshot [pages]
  protocol/Snapshot
  (-revision-id [_] "r0")
  (-size [_] (count pages))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn- page []
  (doto (byte-array 512)
    (aset 16 (byte 2))
    (aset 17 (byte 0))))

(def ^:private snapshot (->Snapshot {"1" (page)}))

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
    (with-redefs [wal/sync (fn [capture]
                             [capture {:database-page-count 1}])]
      (sqlite/sync wal-file)
      (sqlite/sync wal-file))
    (let [multiple (try
                     (evaluation/commit filesystem)
                     nil
                     (catch clojure.lang.ExceptionInfo exception
                       exception))]
      (is (= ::anomaly/incorrect (-> multiple ex-data ::anomaly/category)))
      (is (= ::evaluation/multiple-commits (:reason (ex-data multiple)))))))
