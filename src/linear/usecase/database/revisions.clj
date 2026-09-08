(ns linear.usecase.database.revisions
  (:require
   [clojure.string :as string]
   [linear.spec :refer [spec-for]]))

(defmethod spec-for ::revision-id [_]
  [:and :string [:fn #(string/starts-with? % "r-")]])

(defmethod spec-for ::page-id [_]
  pos-int?)

(defmethod spec-for ::page [_]
  [:fn bytes?])

(defmethod spec-for ::revision [_]
  [:map
   [:revision-id ::revision-id]
   [:parent [:maybe ::revision-id]]
   [:database-page-count nat-int?]
   [:pages [:map-of ::page-id ::page]]])

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

(defprotocol ConsistentView
  (-head [view])
  (-as-of [view revision-id])
  (-changes-since [view target-revision-id client-revision-id]))

(defmethod spec-for ::consistent-view [_]
  [:fn #(satisfies? ConsistentView %)])

(defprotocol ConsistentReadable
  (-read-consistently [reader f database]))

(defn consistent-readable? [value]
  (satisfies? ConsistentReadable value))

(defmethod spec-for ::consistent-readable [_]
  [:fn consistent-readable?])

(defn read-consistently [reader f database]
  (-read-consistently reader f database))

(defmacro with-consistent-view
  [[binding reader database] & body]
  `(read-consistently ~reader
                      (fn [~binding]
                        ~@body)
                      ~database))

(defn head
  {:malli/schema [:-> ::consistent-view ::snapshot]}
  [view]
  (-head view))

(defn as-of
  {:malli/schema [:-> ::consistent-view ::revision-id ::snapshot]}
  [view revision-id]
  (-as-of view revision-id))

(defn changes-since
  {:malli/schema [:-> ::consistent-view ::revision-id ::revision-id [:set ::page-id]]}
  [view target-revision-id client-revision-id]
  (-changes-since view
                  target-revision-id
                  client-revision-id))

(defprotocol RevisionWritable
  (-publish-next! [writer revision database]))

(defn revision-writable? [value]
  (satisfies? RevisionWritable value))

(defmethod spec-for ::revision-writable [_]
  [:fn revision-writable?])

(defn publish-next!
  {:malli/schema [:->
                  ::revision-writable
                  ::revision
                  [:map
                   [:id :string]
                   [:vault [:map [:keychain :any]]]]
                  ::revision]}
  [writer revision database]
  (-publish-next! writer revision database))
