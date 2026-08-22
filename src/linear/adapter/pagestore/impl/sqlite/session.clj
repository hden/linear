(ns linear.adapter.pagestore.impl.sqlite.session
  "Execution-local mutable state for one SQLite invocation."
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.impl.sqlite.wal :as wal]))

(defn new-session [database]
  {:database         database
   :wal              (atom (wal/new-capture))
   :committed-deltas (atom [])
   :closing?         (atom false)
   :failure          (atom nil)})

(defn database [session]
  (:database session))

(defn failure-slot [session]
  (:failure session))

(defn begin-close! [session]
  (reset! (:closing? session) true))

(defn closing? [session]
  @(:closing? session))

(defn throw-if-failed! [session]
  (when-let [failure @(failure-slot session)]
    (if (instance? VirtualMachineError failure)
      (throw failure)
      (throw (ex-info "SQLite VFS callback failed"
                      failure
                      (:cause failure))))))

(defn write-wal! [session offset bytes]
  (swap! (:wal session) wal/write {:offset offset :bytes bytes}))

(defn write-owned-wal! [session offset bytes]
  (swap! (:wal session) wal/write-owned {:offset offset :bytes bytes}))

(defn read-wal [session offset length]
  (wal/read-at @(:wal session) offset length))

(defn wal-size [session]
  (wal/size @(:wal session)))

(defn truncate-wal! [session size]
  (swap! (:wal session) wal/truncate size))

(defn sync-wal! [session]
  (let [delta (volatile! nil)]
    (swap! (:wal session)
           (fn [capture]
             (let [[next-capture next-delta] (wal/sync capture)]
               (vreset! delta next-delta)
               next-capture)))
    (when-let [delta @delta]
      (swap! (:committed-deltas session) conj delta))
    @delta))

(defn committed-deltas [session]
  @(:committed-deltas session))

(defn canonical-revision
  "Returns the committed page image produced by this SQLite invocation.

  Publishing it, assigning an id, and compare-and-set against HEAD belong to
  the Page Store transaction boundary, not to the SQLite session."
  [session]
  (let [deltas (committed-deltas session)]
    (case (count deltas)
      0 (throw (ex-info "SQLite execution did not produce a committed WAL delta"
                        {::anomaly/category ::anomaly/incorrect
                         :reason            ::missing-wal-commit}))
      1 (first deltas)
      ;; else
      (throw (ex-info "SQLite execution produced multiple committed WAL deltas"
                      {::anomaly/category ::anomaly/incorrect
                       :reason            ::multiple-wal-commits
                       :count             (count deltas)})))))
