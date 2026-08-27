(ns linear.usecase.database
  (:require
   [clojure.string :as string]
   [cognitect.anomalies :as anomaly]
   [diehard.core :as diehard]
   [linear.spec :refer [spec-for]])
  (:import
   (java.lang AutoCloseable)))

(defmethod spec-for ::database-id [_]
  [:and :string [:fn #(string/starts-with? % "d-")]])

(defmethod spec-for ::revision-id [_]
  [:and :string [:fn #(string/starts-with? % "r-")]])

(defmethod spec-for ::keychain [_]
  :any)

(defmethod spec-for ::page-id [_]
  pos-int?)

(defmethod spec-for ::page [_]
  [:fn bytes?])

(defmethod spec-for ::database [_]
  [:map
   [:id ::database-id]
   [:display-name :string]
   [:keychain ::keychain]])

(defmethod spec-for ::revision [_]
  [:map
   [:revision-id ::revision-id]
   [:parent [:maybe ::revision-id]]
   [:database-page-count nat-int?]
   [:pages [:map-of ::page-id ::page]]])

(defmethod spec-for ::sqlite-scalar [_]
  [:fn #(or (nil? %)
            (instance? Long %)
            (instance? Double %)
            (string? %)
            (bytes? %))])

(defmethod spec-for ::statement [_]
  [:map
   [:sql :string]
   [:parameters [:vector ::sqlite-scalar]]])

(defmethod spec-for ::push-command [_]
  [:map
   [:statements [:vector ::statement]]])

(defprotocol Snapshot
  (-revision-id [snapshot])
  (-size [snapshot])
  (-fetch-pages-by-ids [snapshot arg-map]))

(defn snapshot? [value]
  (satisfies? Snapshot value))

(defmethod spec-for ::snapshot [_]
  [:fn snapshot?])

(defn revision-id
  {:malli/schema [:-> ::snapshot ::revision-id]}
  [snapshot]
  (-revision-id snapshot))

(defn size
  {:malli/schema [:-> ::snapshot nat-int?]}
  [snapshot]
  (-size snapshot))

(defn fetch-pages-by-ids
  {:malli/schema [:->
                  ::snapshot
                  [:map [:ids [:set ::page-id]]]
                  [:map-of ::page-id [:maybe ::page]]]}
  [snapshot arg-map]
  (-fetch-pages-by-ids snapshot arg-map))

(defprotocol Evaluator
  (-evaluate [evaluator arg-map]))

(defn evaluator? [value]
  (satisfies? Evaluator value))

(defmethod spec-for ::evaluator [_]
  [:fn evaluator?])

(defn evaluate
  {:malli/schema [:->
                  ::evaluator
                  [:map
                   [:snapshot ::snapshot]
                   [:command ::push-command]]
                  ::revision]}
  [evaluator arg-map]
  (-evaluate evaluator arg-map))

(defprotocol SnapshotReader
  (-latest-snapshot [reader database]))

(defn snapshot-reader? [value]
  (satisfies? SnapshotReader value))

(defmethod spec-for ::snapshot-reader [_]
  [:fn snapshot-reader?])

(defn- closeable-snapshot? [value]
  (and (snapshot? value)
       (instance? AutoCloseable value)))

(defmethod spec-for ::closeable-snapshot [_]
  [:fn closeable-snapshot?])

(defn latest-snapshot
  {:malli/schema [:->
                  ::snapshot-reader
                  ::database
                  ::closeable-snapshot]}
  [reader database]
  (-latest-snapshot reader database))

(defprotocol RevisionWriter
  (-publish-next-revision! [writer database revision]))

(defn revision-writer? [value]
  (satisfies? RevisionWriter value))

(defmethod spec-for ::revision-writer [_]
  [:fn revision-writer?])

(defn publish-next-revision!
  {:malli/schema [:->
                  ::revision-writer
                  ::database
                  ::revision
                  ::revision]}
  [writer database revision]
  (-publish-next-revision! writer database revision))

(defn- revision-conflict? [error]
  (= ::revision-conflict (:reason (ex-data error))))

(defn- ensure-descends-from! [parent revision]
  (when-not (= parent (:parent revision))
    (throw (ex-info "Evaluator revision does not descend from the snapshot"
                    {::anomaly/category ::anomaly/fault
                     :reason ::invalid-revision-parent
                     :expected-parent parent
                     :actual-parent (:parent revision)}))))

(defn- attempt-push!
  [{:keys [snapshot-reader revision-writer evaluator]}
   database
   command]
  (let [revision (with-open [^AutoCloseable snapshot
                             (latest-snapshot snapshot-reader database)]
                   (let [parent (revision-id snapshot)
                         evaluated-revision
                         (evaluate evaluator
                                   {:snapshot snapshot
                                    :command command})]
                     (ensure-descends-from! parent evaluated-revision)
                     evaluated-revision))]
    (publish-next-revision! revision-writer database revision)))

(defn push!
  {:malli/schema [:->
                  [:map
                   [:snapshot-reader ::snapshot-reader]
                   [:revision-writer ::revision-writer]
                   [:evaluator ::evaluator]]
                  ::database
                  ::push-command
                  ::revision]}
  [capabilities database command]
  (try
    (diehard/with-retry {:retry-if (fn [_result error]
                                     (revision-conflict? error))
                         :max-retries 2
                         :backoff-ms [10 250 2.0]
                         :jitter-factor 0.5}
      (attempt-push! capabilities database command))
    (catch clojure.lang.ExceptionInfo error
      (if (revision-conflict? error)
        (throw (ex-info "Database push conflict"
                        {::anomaly/category ::anomaly/conflict
                         :reason ::push-conflict
                         :attempts 3}
                        error))
        (throw error)))))
