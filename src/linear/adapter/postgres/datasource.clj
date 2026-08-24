(ns linear.adapter.postgres.datasource
  (:require
   [linear.adapter.postgres.core :as core]
   [linear.protocol :as protocol])
  (:import
   (javax.sql DataSource)))

(extend-protocol protocol/Checkable
  DataSource
  (-ready? [datasource]
    (pos-int? (core/query datasource {:statement {:select [[[:count :*] :count]]
                                                  :from   :ragtime-migrations}
                                      :parse-fn  #(get-in % [0 :count])})))
  (-ok? [_datasource]
    true))
