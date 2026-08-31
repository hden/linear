(ns linear.adapter.postgres.core
  (:require
   [clojure.string :as string]
   [cognitect.anomalies :as anomaly]
   [diehard.core :refer [with-timeout]]
   [hden.ulid :refer [ulid]]
   [honey.sql :as honeysql]
   [linear.spec :refer [spec-for]]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time :as date-time]
   [next.jdbc.result-set :as result-set])
  (:import
   (java.sql Connection SQLException)
   (javax.sql DataSource)
   (org.postgresql.util PSQLException ServerErrorMessage)))

(date-time/read-as-instant)

(defn datasource? [x]
  (instance? DataSource x))

(defmethod spec-for ::datasource [_]
  [:fn datasource?])

(defn connection? [x]
  (instance? Connection x))

(defmethod spec-for ::connection [_]
  [:fn connection?])

(defn rollback [^Connection tx]
  (.rollback tx))

(def ^:const ^:private unique-violation "23505")
(def ^:const ^:private transaction-rollback "40")
(def ^:const ^:private lock-not-available "55P03")
(def ^:const ^:private transactions-idempotency "transactions_idempotency")

(defmethod spec-for ::statement [_]
  [:map])

(defn- sql-exception [error]
  (some #(when (instance? SQLException %) %)
        (take-while some? (iterate ex-cause error))))

(defn- sql-state [error]
  (when-let [^SQLException exception (sql-exception error)]
    (.getSQLState exception)))

(def ^:const ^:private serialization-failure "40001")

(defn serialization-failure? [error]
  (or (= serialization-failure (:sql-state (ex-data error)))
      (= serialization-failure (sql-state error))))

(def default-retry-policy
  {:retry-if      (fn [_ e]
                    (serialization-failure? e))
   :max-retries   5
   :backoff-ms    [10 200]
   :jitter-factor 0.5})

(defn- sql-class [state]
  (when (and state (<= 2 (count state)))
    (subs state 0 2)))

(defn- constraint [error]
  (when-let [error (sql-exception error)]
    (when (instance? PSQLException error)
      (when-let [^ServerErrorMessage server-error
                 (.getServerErrorMessage ^PSQLException error)]
        (.getConstraint server-error)))))

(defn- sql-category [state constraint]
  (cond
    (and (= unique-violation state)
         (= transactions-idempotency constraint))
    ::anomaly/conflict

    (= unique-violation state)
    ::anomaly/incorrect

    (= transaction-rollback (sql-class state))
    ::anomaly/conflict

    (= lock-not-available state)
    ::anomaly/conflict

    (contains? #{"22" "23"} (sql-class state))
    ::anomaly/incorrect

    (contains? #{"08" "53" "57" "58"} (sql-class state))
    ::anomaly/unavailable

    :else
    ::anomaly/fault))

(defn transaction-idempotency-conflict? [error]
  (let [data (ex-data error)]
    (and (= ::anomaly/conflict (::anomaly/category data))
         (= ::transaction-idempotency-conflict (:reason data)))))

(defn- anomaly-message [category]
  (case category
    ::anomaly/incorrect   "PostgreSQL rejected the request"
    ::anomaly/conflict    "PostgreSQL transaction conflict"
    ::anomaly/unavailable "PostgreSQL is unavailable"
    "PostgreSQL operation failed"))

(defn- translate-error [operation error]
  (if (::anomaly/category (ex-data error))
    error
    (let [state    (sql-state error)
          constraint (constraint error)
          category (sql-category state constraint)
          reason   (if (and (= unique-violation state)
                            (= transactions-idempotency constraint))
                     ::transaction-idempotency-conflict
                     ::postgres-error)
          message  (if (= ::transaction-idempotency-conflict reason)
                     "Transaction idempotency key already exists"
                     (anomaly-message category))]
      (ex-info message
               (cond-> {::anomaly/category category
                        ::anomaly/message  message
                        :linear.error/source :postgres
                        :linear.error/operation operation
                        :reason reason}
                 state (assoc :sql-state state)
                 constraint (assoc :constraint constraint))
               error))))

(defn query
  {:malli/schema [:-> [:or ::datasource ::connection]
                      [:map
                       [:statement ::statement]
                       [:timeout-ms {:optional true} pos-int?]
                       [:parse-fn {:optional true} fn?]]
                      :any]}
  [tx {:keys [statement timeout-ms parse-fn]
       :or {timeout-ms 2000
            parse-fn identity}}]
  (try
    (with-timeout {:timeout-ms timeout-ms :interrupt? true}
      (parse-fn (jdbc/execute! tx
                               (honeysql/format statement)
                               {:builder-fn result-set/as-unqualified-kebab-maps})))
    (catch Exception ex
      (throw (translate-error :query ex)))))

(defn transaction-id []
  (str "tx-" (ulid)))

(defmethod spec-for ::transaction-id [_]
  [:and [:string]
        [:fn #(string/starts-with? % "tx-")]])

(defn transact!
  {:malli/schema [:-> ::connection
                      [:map
                       [:statements [:sequential ::statement]]
                       [:timeout-ms {:optional true} pos-int?]
                       [:tx-id {:optional true} ::transaction-id]]
                      :any]}
  [tx {:keys [statements timeout-ms tx-id]
       :or {timeout-ms 2000
            tx-id      (transaction-id)}}]
  (try
    (with-timeout {:timeout-ms timeout-ms :interrupt? true}
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
              statements)))
    (catch Exception ex
      (throw (translate-error :transact ex)))))
