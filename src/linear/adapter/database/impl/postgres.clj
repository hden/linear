(ns linear.adapter.database.impl.postgres
  (:require
   [cognitect.anomalies :as anomaly]
   [diehard.core :refer [with-retry with-timeout]]
   [honey.sql :as honeysql]
   [integrant.core :as ig]
   [linear.adapter.database.core :as core]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as date-time]
   [next.jdbc.result-set :as result-set])
  (:import
   (javax.sql DataSource)))

(def ^:const ^:private serialization-failure "40001")

(def default-retry-policy
  {:retry-if      (fn [_ e]
                    (and (instance? java.sql.SQLException e)
                         (= serialization-failure
                            (.getSQLState ^java.sql.SQLException e))))
   :max-retries   5
   :backoff-ms    [10 200]
   :jitter-factor 0.5})

(extend-protocol core/Connection
  DataSource
  (-query [datasource {:keys [statement timeout-ms parse-fn]
                       :or {timeout-ms 2000
                            parse-fn identity}}]
    (try
      (with-timeout {:timeout-ms timeout-ms :interrupt? true}
        (with-retry default-retry-policy
          (jdbc/with-transaction [tx datasource {:isolation :serializable :read-only true}]
            (parse-fn (jdbc/execute! tx
                                     (honeysql/format statement)
                                     {:builder-fn result-set/as-unqualified-kebab-maps})))))
      (catch Exception e
        ;; TODO: tranlate to domain error
        (throw e))))

  (-transact! [datasource {:keys [statements timeout-ms tx-id]}]
    (try
      (with-timeout {:timeout-ms timeout-ms :interrupt? true}
        (with-retry default-retry-policy
          (jdbc/with-transaction [tx datasource {:isolation :serializable}]
            (let [execute-statement! (fn [{:keys [statement parse-fn guard-fn]
                                           :or {parse-fn identity
                                                guard-fn (constantly nil)}}]
                                       (let [parsed-rows (parse-fn
                                                           (jdbc/execute! tx
                                                                          (honeysql/format statement {:params {:created-by tx-id}})
                                                                          {:builder-fn result-set/as-unqualified-kebab-maps}))]
                                         (when-let [anomaly (guard-fn parsed-rows)]
                                           (throw (ex-info (or (::anomaly/message anomaly) "anomaly")
                                                           anomaly)))
                                         (seq parsed-rows)))]
              (into {}
                    (mapcat execute-statement!)
                    statements)))))
      (catch Exception ex
        ;; TODO: translate to domain error
        (throw ex)))))

(defmethod ig/init-key :linear.adapter.database.impl/postgres [_ options]
  (date-time/read-as-instant)
  options)
