(ns linear.adapter.database.core
  "Public APIs"
  (:require
   [linear.spec :refer [spec-for]]))

(defprotocol Connection
  (-query [conn arg-map])
  (-transact! [conn arg-map]))

(defn conn? [x]
  (satisfies? Connection x))

(defmethod spec-for ::connection [_]
  [:fn conn?])

(defmethod spec-for ::statement [_]
  [:map
   [:statement map?]
   [:parse-fn {:optional true} ifn?]])

(defn query
  {:malli/schema [:-> ::connection
                      ::statement
                      :any]}
  [conn arg-map]
  (-query conn arg-map))

(defn transact!
  {:malli/schema [:-> ::connection
                      [:map
                       [:statements [:or
                                     [:sequential ::statement]
                                     [:set ::statement]]]
                       [:timeout-ms {:optional true} pos-int?]]
                      :any]}
  [conn arg-map]
  (-transact! conn arg-map))
