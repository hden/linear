(ns linear.adapter.postgres.controllable
  (:require
   [linear.adapter.postgres.core :as core]
   [linear.protocol :as protocol])
  (:import
   (javax.sql DataSource)))

(extend-protocol protocol/Controllable
  DataSource
  (-ready? [datasource]
    (pos-int? (core/query datasource {:statement {:select [[[:count :*] :count]]
                                                  :from   :ragtime-migrations}
                                      :parse-fn  #(get-in % [0 :count])}))))

(defn ready?
  {:malli/schema [:-> ::protocol/controllable
                      :boolean]}
  [controllable]
  (protocol/-ready? controllable))
