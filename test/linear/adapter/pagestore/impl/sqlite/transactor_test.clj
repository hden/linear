(ns linear.adapter.pagestore.impl.sqlite.transactor-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [integrant.core :as integrant]
   [linear.adapter.pagestore.impl.core :as pagestore]
   [linear.adapter.pagestore.impl.sqlite.driver :as driver]
   [linear.adapter.pagestore.impl.sqlite.fixture :as fixture]
   [linear.adapter.pagestore.impl.sqlite.session :as session]
   [linear.adapter.pagestore.impl.sqlite.transactor :as transactor]
   [linear.test :refer [catch-ex-data]]))

(deftest ingest-applies-an-operation-to-a-snapshot-and-returns-a-revision
  (let [name       (str "linear-test-" (random-uuid))
        runtime    (driver/install-vfs! {:library (fixture/sqlite-library) :name name})
        snapshot   (fixture/database "r0" (fixture/sqlite-image))
        transactor (transactor/transactor runtime)]
    (try
      (let [revision (pagestore/ingest
                       transactor
                       {:database  snapshot
                        :operation (fn [execute-sql]
                                     (execute-sql "UPDATE t SET value='captured'"))})]
        (is (string? (:revision-id revision)))
        (is (= "r0" (:parent revision)))
        (is (pos? (:database-page-count revision)))
        (is (seq (:pages revision)))
        (is (empty? @(:sessions runtime)))
        (is (empty? @(:files runtime))))
      (finally
        (driver/close-vfs! runtime)))))

(deftest failed-ingest-discards-its-virtual-sqlite-execution
  (let [runtime    (driver/install-vfs! {:library (fixture/sqlite-library)
                                         :name    (str "linear-test-" (random-uuid))})
        snapshot   (fixture/database "r0" (fixture/sqlite-image))
        transactor (transactor/transactor runtime)]
    (try
      (let [error (catch-ex-data
                    #(pagestore/ingest transactor
                       {:database  snapshot
                        :operation (fn [execute-sql]
                                     (execute-sql "UPDATE absent SET value='captured'"))}))]
        (is (= ::anomaly/fault (::anomaly/category error)))
        (is (= :execute (:operation error)))
        (is (empty? @(:sessions runtime)))
        (is (empty? @(:files runtime))))
      (finally
        (driver/close-vfs! runtime)))))

(deftest ingest-translates-an-unexpected-operation-throwable
  (let [runtime    (driver/install-vfs! {:library (fixture/sqlite-library)
                                         :name    (str "linear-test-" (random-uuid))})
        snapshot   (fixture/database "r0" (fixture/sqlite-image))
        transactor (transactor/transactor runtime)
        cause      (AssertionError. "unexpected operation failure")]
    (try
      (let [error (try
                    (pagestore/ingest transactor
                      {:database  snapshot
                       :operation (fn [_]
                                    (throw cause))})
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      error))]
        (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
        (is (= :transact (-> error ex-data :operation)))
        (is (identical? cause (.getCause error)))
        (is (empty? @(:sessions runtime)))
        (is (empty? @(:files runtime))))
      (finally
        (driver/close-vfs! runtime)))))

(deftest sqlite-call-and-attempt-rethrow-virtual-machine-errors
  (let [sqlite-session (session/new-session ::database)
        cause          (OutOfMemoryError. "sqlite execution allocation failed")
        call-error     (try
                         (#'transactor/call-sqlite sqlite-session {:operation :execute}
                           #(throw cause))
                         nil
                         (catch Throwable error
                           error))
        attempt-error  (try
                         (#'transactor/attempt #(throw cause))
                         nil
                         (catch Throwable error
                           error))]
    (is (identical? cause call-error))
    (is (identical? cause attempt-error))))

(deftest transactor-is-an-integrant-component
  (let [runtime    (driver/install-vfs! {:library (fixture/sqlite-library)
                                         :name    (str "linear-test-" (random-uuid))})
        transactor (integrant/init-key :linear.adapter.pagestore.impl.sqlite/transactor
                                       {:vfs runtime})]
    (try
      (is (pagestore/transactor? transactor))
      (finally
        (driver/close-vfs! runtime)))))
