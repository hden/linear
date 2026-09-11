(ns linear.adapter.slatedb.connection-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi])
  (:import
   (java.lang AutoCloseable)))

(deftest database-lease-ends-after-the-scoped-operation
  (let [events (atom [])
        connection (connection/connection
                     {:borrow-database     (fn [database-id]
                                             (swap! events conj [:borrow database-id])
                                             ::database)
                      :return-database     (fn [database-id database]
                                             (swap! events conj [:return database-id database]))
                      :close-resources     (fn [])})]
    (with-open [_database (connection/database connection {:database-id "d-1"})]
      (swap! events conj :call))
    (is (= [[:borrow "d-1"]
            :call
            [:return "d-1" ::database]]
           @events))))

(deftest database-is-returned-after-a-fenced-operation
  (let [events (atom [])
        failure (ex-info "fenced"
                         {::anomaly/category ::anomaly/unavailable
                          :reason            ::ffi/fenced})
        connection (connection/connection
                     {:borrow-database     (constantly ::database)
                      :return-database     (fn [database-id database]
                                             (swap! events conj [:return database-id database]))
                      :close-resources     (fn [])})
        caught (try
                 (with-open [_database (connection/database connection {:database-id "d-1"})]
                   (throw failure))
                 (catch Exception error
                   error))]
    (is (identical? failure caught))
    (is (= [[:return "d-1" ::database]] @events))))

(deftest return-failure-is-propagated-on-close
  (let [events     (atom [])
        return-error (ex-info "return failed" {})
        connection (connection/connection
                     {:borrow-database     (constantly ::database)
                      :return-database     (fn [_ _]
                                             (swap! events conj :return)
                                             (throw return-error))
                      :close-resources     (fn [])})
        caught (try
                 (with-open [_database (connection/database connection {:database-id "d-1"})]
                   :done)
                 (catch Exception error
                   error))]
    (is (identical? return-error caught))
    (is (= [:return] @events))))

(deftest database-lease-is-returned-only-once
  (let [returns    (atom 0)
        connection (connection/connection
                     {:borrow-database (constantly ::database)
                      :return-database (fn [_ _]
                                         (swap! returns inc))
                      :close-resources (fn [])})
        database   (connection/database connection {:database-id "d-1"})]
    (.close ^AutoCloseable database)
    (.close ^AutoCloseable database)
    (is (= 1 @returns))))

(deftest close-releases-owned-resources
  (let [closed? (atom false)
        connection (connection/connection
                     {:borrow-database     (fn [_])
                      :return-database     (fn [_ _])
                      :close-resources     #(reset! closed? true)})]
    (is (nil? (connection/close connection)))
    (is @closed?)))
