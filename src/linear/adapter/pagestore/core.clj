(ns linear.adapter.pagestore.core
  "Public APIs"
  (:require
   [linear.spec :refer [spec-for]]))

(defprotocol Connection
  (-create-db! [conn arg-map])
  (-execute! [conn db f arg-map]))

(defn conn? [x]
  (satisfies? Connection x))

(defmethod spec-for ::connection [_]
  [:fn conn?])

(defmethod spec-for ::id [_]
  [:and :string
        [:fn #(not (empty? %))]])

(defn create-db!
  {:malli/schema [:-> ::connection
                      [:map
                       [:db ::id]
                       [:from {:optional true} ::id]]
                      :any]}
  [conn arg-map]
  (-create-db! conn arg-map))

(defn execute!
  ([conn db f]
   (execute! conn db f {}))
  ([conn db f arg-map]
   (-execute! conn db f arg-map)))
