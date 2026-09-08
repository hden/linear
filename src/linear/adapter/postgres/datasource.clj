(ns linear.adapter.postgres.datasource
  (:require
   [diehard.core :refer [with-retry with-timeout]]
   [linear.adapter.postgres.core :as core]
   [linear.usecase.healthcheck :as healthcheck]
   [linear.usecase.transaction :as transaction]
   [next.jdbc :as jdbc])
  (:import
   (javax.sql DataSource)))

(extend-protocol healthcheck/Checkable
  DataSource
  (-ready? [datasource]
    (pos-int? (core/query datasource {:statement {:select [[[:count :*] :count]]
                                                  :from   :ragtime-migrations}
                                      :parse-fn  #(get-in % [0 :count])})))
  (-ok? [_datasource]
    true))

(extend-protocol transaction/Transactable
  DataSource
  (-transact [datasource f {:keys [read-only timeout-ms]
                            :or {timeout-ms 2000}}]
    (with-timeout {:timeout-ms timeout-ms :interrupt? true}
      (with-retry core/default-retry-policy
        (jdbc/with-transaction [tx datasource {:isolation :serializable
                                               :read-only read-only}]
          (f tx))))))
