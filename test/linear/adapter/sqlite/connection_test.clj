(ns linear.adapter.sqlite.connection-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.connection :as connection]
   [linear.adapter.sqlite.evaluation :as evaluation]
   [linear.adapter.sqlite.test-support :as support]
   [linear.adapter.sqlite.vfs :as vfs])
  (:import
   (java.util UUID)))

(defn- call-with-native-connection [library f]
  (let [identifier (str (UUID/randomUUID))
        path       (str "/linear/" identifier ".db")
        resources  (vfs/install {:library library
                                 :name    (str "test-" identifier)})]
    (try
      (let [invocation (vfs/mount resources
                                  {:path       path
                                   :filesystem (evaluation/evaluation
                                                 (support/snapshot
                                                   (support/sqlite-image))
                                                 path)})]
        (try
          (let [database (connection/open {:path     path
                                           :vfs-name (:name resources)})]
            (try
              (f database)
              (finally
                (connection/close database))))
          (finally
            (vfs/unmount invocation))))
      (finally
        (vfs/drain! resources)
        (vfs/uninstall resources)))))

(deftest execute-translates-native-sqlite-errors
  (when-let [library (System/getenv "SQLITE_LIBRARY")]
    (call-with-native-connection
      library
      (fn [database]
        (let [failure (try
                        (connection/execute database "not valid SQL")
                        nil
                        (catch clojure.lang.ExceptionInfo error
                          error))
              data    (ex-data failure)]
          (is (= ::anomaly/fault (::anomaly/category data)))
          (is (= ::connection/statement-failed (:reason data)))
          (is (pos-int? (:code data)))
          (is (pos-int? (:extended-code data)))
          (is (string/includes? (:sqlite-message data) "syntax error")))))))

(deftest execute-rejects-a-closed-connection
  (when-let [library (System/getenv "SQLITE_LIBRARY")]
    (call-with-native-connection
      library
      (fn [database]
        (connection/close database)
        (let [failure (try
                        (connection/execute database "SELECT 1")
                        nil
                        (catch clojure.lang.ExceptionInfo error
                          error))]
          (is (= ::connection/connection-closed
                 (:reason (ex-data failure)))))))))

(deftest close-is-idempotent
  (when-let [library (System/getenv "SQLITE_LIBRARY")]
    (call-with-native-connection
      library
      (fn [database]
        (connection/close database)
        (is (nil? (connection/close database)))))))
