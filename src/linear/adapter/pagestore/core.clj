(ns linear.adapter.pagestore.core
  "Public APIs"
  (:require
   [linear.adapter.pagestore.impl.core :as impl]))

(defn create-db!
  {:malli/schema [:-> ::connection
                      [:map
                       [:db ::id]
                       [:from {:optional true} ::id]]
                      :any]}
  [conn arg-map]
  (impl/-create-db! conn arg-map))
