(ns linear.adapter.postgres.core
  (:require
   [clj-ulid :refer [ulid]]
   [clojure.string :as string]
   [cognitect.anomalies :as anomaly]
   [diehard.core :refer [with-retry with-timeout]]
   [honey.sql :as honeysql]
   [linear.spec :refer [spec-for]]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as date-time]
   [next.jdbc.result-set :as result-set])
  (:import
   (javax.sql DataSource)))

(date-time/read-as-instant)

(defn datasource? [x]
  (instance? DataSource x))

(defmethod spec-for ::datasource [_]
  [:fn datasource?])

(def ^:const ^:private serialization-failure "40001")

(def default-retry-policy
  {:retry-if      (fn [_ e]
                    (and (instance? java.sql.SQLException e)
                         (= serialization-failure
                            (.getSQLState ^java.sql.SQLException e))))
   :max-retries   5
   :backoff-ms    [10 200]
   :jitter-factor 0.5})

(defmethod spec-for ::statement [_]
  [:map])

(defn query
  {:malli/schema [:-> ::datasource
                      [:map
                       [:statement ::statement]
                       [:timeout-ms {:optional true} pos-int?]
                       [:parse-fn {:optional true} fn?]]
                      :any]}
  [datasource {:keys [statement timeout-ms parse-fn]
               :or {timeout-ms 2000
                    parse-fn identity}}]
  (try
    (with-timeout {:timeout-ms timeout-ms :interrupt? true}
      (with-retry default-retry-policy
        (jdbc/with-transaction [tx datasource {:isolation :serializable :read-only true}]
          (parse-fn (jdbc/execute! tx
                                   (honeysql/format statement)
                                   {:builder-fn result-set/as-unqualified-kebab-maps})))))
    (catch Exception ex
      (tap> ex)
      ;; TODO: tranlate to domain error
      (throw ex))))

(defn transaction-id []
  (str "tx-" (ulid)))

(defmethod spec-for ::transaction-id [_]
  [:and [:string]
        [:fn #(string/starts-with? % "tx-")]])

(defn transact!
  {:malli/schema [:-> ::datasource
                      [:map
                       [:statements [:sequential ::statement]]
                       [:timeout-ms {:optional true} pos-int?]
                       [:tx-id {:optional true} ::transaction-id]]
                      :any]}
  [datasource {:keys [statements timeout-ms tx-id]
               :or {tx-id (transaction-id)}}]
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
      (tap> ex)
      ;; TODO: translate to domain error
      (throw ex))))
