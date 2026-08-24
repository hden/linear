(ns linear.adapter.sqlite.connection-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.connection :as connection]
   [linear.adapter.sqlite.ffi :as ffi]))

(deftest execute-translates-sqlite-errors
  (let [database (connection/->Connection ::handle (atom false))
        failure (atom nil)]
    (with-redefs [ffi/exec (fn [& _] 1)
                  ffi/extended-errcode (constantly 257)
                  ffi/errmsg (constantly "bad statement")]
      (try
        (connection/execute database "select 1")
        (catch clojure.lang.ExceptionInfo exception
          (reset! failure exception))))
    (is (= {::anomaly/category ::anomaly/fault
            :reason ::connection/statement-failed
            :sql "select 1"
            :code 1
            :extended-code 257
            :sqlite-message "bad statement"}
           (ex-data @failure)))))

(deftest execute-rejects-a-closed-connection-before-ffi
  (let [calls (atom 0)
        database (connection/->Connection ::handle (atom true))]
    (with-redefs [ffi/exec (fn [& _] (swap! calls inc))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (connection/execute database "select 1")))
      (is (zero? @calls)))))

(deftest close-is-idempotent
  (let [calls    (atom 0)
        database (connection/->Connection ::handle (atom false))]
    (with-redefs [ffi/close (fn [_]
                              (swap! calls inc)
                              ffi/sqlite-ok)]
      (connection/close database)
      (connection/close database)
      (is (= 1 @calls)))))
