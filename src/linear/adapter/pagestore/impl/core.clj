(ns linear.adapter.pagestore.impl.core
  "Internal APIs"
  (:require
   [linear.spec :refer [spec-for]]))

(defmethod spec-for ::id [_]
  [:and :string
        [:fn #(not (empty? %))]])

(defprotocol Connection
  (-create-db! [conn arg-map])
  (-execute! [conn database-id f arg-map]))

(defn conn? [x]
  (satisfies? Connection x))

(defmethod spec-for ::connection [_]
  [:fn conn?])

(defprotocol Database
  (-revision-id [db])
  (-size [db])
  (-fetch-pages-by-ids [db arg-map]))

(defn db? [x]
  (satisfies? Database x))

(defmethod spec-for ::database [_]
  [:fn db?])

(defn size
  {:malli/schema [:-> ::database
                      :int]}
  [db]
  (-size db))

(defn revision-id
  {:malli/schema [:-> ::database
                      ::id]}
  [db]
  (-revision-id db))

(defn fetch-pages-by-ids
  {:malli/schema [:-> ::database
                      [:map
                       [:ids [:set ::id]]]
                      [:map]]}
  [db arg-map]
  (-fetch-pages-by-ids db arg-map))

(defprotocol Transactor
  "What if SQLite is just a function that returns? (instead of writing to files)"
  (-ingest [transactor arg-map])
  (-vacuum [transactor arg-map]))

(defn transactor? [x]
  (satisfies? Transactor x))

(defmethod spec-for ::transactor [_]
  [:fn transactor?])

(defn ingest
  "extract diffs from applying changes
   VFS. no visible side-effects
   returns a revision"
  {:malli/schema [:-> ::transactor
                      [:map]
                      [:map]]}
  [transactor arg-map]
  (-ingest transactor arg-map))

(defn vacuum
  "extract diffs from applying incremental vacuum
   VFS. no visible side-effects
   returns a revision"
  {:malli/schema [:-> ::transactor
                      [:map]
                      [:map]]}
  [transactor arg-map]
  (-vacuum transactor arg-map))
