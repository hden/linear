(ns linear.adapter.sqlite.evaluator-integration-test
  (:require
   [clojure.test :refer [deftest is]]
   [integrant.core :as integrant]
   [linear.adapter.sqlite.evaluator]
   [linear.protocol :as protocol])
  (:import
   (java.nio.file Files)
   (java.sql DriverManager)
   (java.util Arrays)))

(defrecord Snapshot [revision-id pages]
  protocol/Snapshot
  (-revision-id [_]
    revision-id)
  (-size [_]
    (count pages))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn- page-size [^bytes image]
  (let [value (bit-or (bit-shift-left (bit-and (aget image 16) 0xff) 8)
                      (bit-and (aget image 17) 0xff))]
    (if (= value 1) 65536 value)))

(defn- snapshot [image]
  (let [size  (page-size image)
        page-count (quot (alength image) size)]
    (->Snapshot "r0"
                (into {}
                      (map (fn [page-number]
                             [(str page-number)
                              (Arrays/copyOfRange image
                                (* (dec page-number) size)
                                (* page-number size))]))
                      (range 1 (inc page-count))))))

(defn- sqlite-image []
  (let [path (Files/createTempFile "linear-sqlite-evaluator" ".db"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [connection (DriverManager/getConnection (str "jdbc:sqlite:" path))
                  statement  (.createStatement connection)]
        (.execute statement "PRAGMA journal_mode=WAL")
        (.execute statement "CREATE TABLE t (id INTEGER PRIMARY KEY, value TEXT)")
        (.execute statement "INSERT INTO t (value) VALUES ('before-evaluator')"))
      (Files/readAllBytes path)
      (finally
        (Files/deleteIfExists path)))))

(deftest evaluates-sql-through-the-native-evaluator
  (when-let [library (System/getenv "SQLITE_LIBRARY")]
    (Class/forName "org.sqlite.JDBC")
    (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {:library library})]
      (try
        (let [result (protocol/evaluate evaluator
                       {:snapshot  (snapshot (sqlite-image))
                        :operation (fn [execute-sql]
                                     (execute-sql "UPDATE t SET value = 'through-evaluator' WHERE id = 1"))})]
          (is (= "r0" (:parent result)))
          (is (pos? (:database-page-count result)))
          (is (seq (:pages result))))
        (finally
          (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator))))))

(deftest computes-independent-revisions-from-the-same-snapshot
  (when-let [library (System/getenv "SQLITE_LIBRARY")]
    (Class/forName "org.sqlite.JDBC")
    (let [base (snapshot (sqlite-image))
          evaluator  (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {:library library})]
      (try
        (let [evaluate-async (fn [value]
                               (future
                                 (protocol/evaluate
                                   evaluator
                                   {:snapshot  base
                                    :operation (fn [execute-sql]
                                                 (execute-sql
                                                   (str "UPDATE t SET value = '" value "' WHERE id = 1")))})))
              left           (evaluate-async "left")
              right          (evaluate-async "right")]
          (is (= "r0" (:parent @left)))
          (is (= "r0" (:parent @right)))
          (is (not= (:revision-id @left) (:revision-id @right))))
        (finally
          (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator))))))
