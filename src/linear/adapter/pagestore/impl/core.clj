(ns linear.adapter.pagestore.impl.core
  "Internal APIs"
  (:require
   [linear.adapter.pagestore.core :as core]
   [linear.spec :refer [spec-for]]))

(defprotocol Database
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

(defn fetch-pages-by-ids
  {:malli/schema [:-> ::database
                      [:map
                       [:ids [:set ::core/id]]]
                      [:map]]}
  [db arg-map]
  (-fetch-pages-by-ids db arg-map))
