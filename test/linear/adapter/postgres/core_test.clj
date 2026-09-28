(ns linear.adapter.postgres.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres.core :as core]
   [linear.test :refer [run]])
  (:import
   (java.sql SQLException)
   (org.postgresql.util PSQLException ServerErrorMessage)))

(defn- postgres-error [sql-state constraint]
  (PSQLException.
    (ServerErrorMessage.
      (str "SERROR\u0000C" sql-state
           "\u0000Mdatabase error"
           (when constraint (str "\u0000n" constraint))
           "\u0000\u0000"))))

(defn- translate [operation error]
  (#'core/translate-error operation error))

(defn- caused-by? [class error]
  (some #(instance? class %)
        (take-while some? (iterate ex-cause error))))

(deftest translates-idempotency-conflict
  (let [error (translate :transact
                         (postgres-error "23505" "transactions_idempotency"))]
    (is (= ::anomaly/conflict
           (::anomaly/category (ex-data error))))
    (is (= ::core/transaction-idempotency-conflict
           (:reason (ex-data error))))
    (is (caused-by? PSQLException error))))

(deftest classifies-postgres-errors
  (doseq [[sql-state constraint category]
          [["23503" nil ::anomaly/incorrect]
           ["23505" "transactions_pkey" ::anomaly/incorrect]
           ["22023" nil ::anomaly/incorrect]
           ["40001" nil ::anomaly/conflict]
           ["55P03" nil ::anomaly/conflict]
           ["08006" nil ::anomaly/unavailable]
           ["53000" nil ::anomaly/unavailable]
           ["42P01" nil ::anomaly/fault]
           ["99999" nil ::anomaly/fault]
           ["9" nil ::anomaly/fault]]]
    (testing sql-state
      (let [exception (if constraint
                        (postgres-error sql-state constraint)
                        (SQLException. "database error" sql-state))
            error (translate :transact exception)]
        (is (= category
               (::anomaly/category (ex-data error))))))))

(deftest retry-policy-recognizes-translated-serialization-failure
  (let [error (ex-info "PostgreSQL transaction conflict"
                       {::anomaly/category ::anomaly/conflict
                        :sql-state "40001"})]
    (is ((:retry-if core/default-retry-policy) 1 error))))

(deftest preserves-existing-anomaly
  (let [error (ex-info "already translated"
                       {::anomaly/category ::anomaly/unavailable})]
    (is (identical? error (translate :query error)))))

(deftest ^:integration query
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp sys)]
      (is (core/datasource? datasource))
      (is (<= 1
              (core/query datasource {:statement {:select [[[:count :*] :count]]
                                                  :from :ragtime-migrations}
                                      :parse-fn #(get-in % [0 :count])}))))))
