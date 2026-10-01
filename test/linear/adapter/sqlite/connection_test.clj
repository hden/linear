(ns linear.adapter.sqlite.connection-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.connection :as connection]
   [linear.adapter.sqlite.evaluation :as evaluation]
   [linear.adapter.sqlite.vfs :as vfs]
   [linear.test-data.sqlite :as sqlite-data])
  (:import
   (java.util UUID)))

(defn- call-with-native-connection [f]
  (let [identifier (str (UUID/randomUUID))
        path       (str "/linear/" identifier ".db")
        resources  (vfs/install {:name (str "test-" identifier)})]
    (try
      (let [invocation (vfs/mount resources
                                  {:path       path
                                   :filesystem (evaluation/evaluation (sqlite-data/snapshot
                                                                        {:image (sqlite-data/sqlite-image)}) {:path path})})]
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

(deftest ^:integration execute-translates-native-sqlite-errors
  (call-with-native-connection
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
        (is (string/includes? (:sqlite-message data) "syntax error"))))))

(deftest ^:integration execute-rejects-a-closed-connection
  (call-with-native-connection
    (fn [database]
      (connection/close database)
      (let [failure (try
                      (connection/execute database "SELECT 1")
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        error))]
        (is (= ::connection/connection-closed
               (:reason (ex-data failure))))))))

(deftest ^:integration constraint-error-survives-statement-finalization
  (call-with-native-connection
    (fn [database]
      (connection/execute database "PRAGMA locking_mode = EXCLUSIVE")
      (connection/execute database "CREATE TABLE unique_ids (id INTEGER PRIMARY KEY); INSERT INTO unique_ids VALUES (1)")
      (let [failure (try
                      (connection/execute-statement database {:sql "INSERT INTO unique_ids VALUES (1)" :parameters []})
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::connection/statement-failed (:reason (ex-data failure))))
        (is (string/includes? (:sqlite-message (ex-data failure)) "UNIQUE constraint failed"))
        (is (= [1] (connection/query-integers database {:sql "SELECT COUNT(*) FROM unique_ids" :parameters []})))))))

(deftest ^:integration close-is-idempotent
  (call-with-native-connection
    (fn [database]
      (connection/close database)
      (is (nil? (connection/close database))))))
