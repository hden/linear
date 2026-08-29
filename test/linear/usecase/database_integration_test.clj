(ns linear.usecase.database-integration-test
  (:require
   [clojure.test :refer [deftest is]]
   [integrant.core :as integrant]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.store]
   [linear.adapter.sqlite.evaluator]
   [linear.adapter.sqlite.test-support :as support]
   [linear.usecase.database :as database]
   [taoensso.tempel :as tempel])
  (:import
   (java.nio.file Files)
   (java.sql DriverManager)
   (java.util Arrays)))

(defn- page-size [image]
  (let [value (bit-or (bit-shift-left (bit-and (aget ^bytes image 16) 0xff) 8)
                      (bit-and (aget ^bytes image 17) 0xff))]
    (if (= value 1) 65536 value)))

(defn- sqlite-pages []
  (let [path (Files/createTempFile
               "linear-database-push" ".db"
               (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [database (DriverManager/getConnection (str "jdbc:sqlite:" path))
                  statement (.createStatement database)]
        (.execute statement "PRAGMA journal_mode=WAL")
        (.execute statement "CREATE TABLE t (id INTEGER PRIMARY KEY, value TEXT)")
        (.execute statement "INSERT INTO t (value) VALUES ('initial')"))
      (let [image      (Files/readAllBytes path)
            size       (page-size image)
            page-count (quot (alength image) size)]
        (into {}
              (map (fn [page-number]
                     [page-number
                      (Arrays/copyOfRange image
                                          (* (dec page-number) size)
                                          (* page-number size))]))
              (range 1 (inc page-count))))
      (finally
        (Files/deleteIfExists path)))))

(defn- root-records [keychain pages]
  (let [revision     {:revision-id         "r-root"
                      :parent              nil
                      :database-page-count (count pages)
                      :pages               pages}
        revision-key (key/revision (:revision-id revision))]
    (into [[revision-key
            (codec/encode-revision keychain revision-key revision)]]
          (concat
            (map (fn [[page-id page]]
                   (let [page-key (key/page page-id)]
                     [page-key (codec/encode-page keychain page-key page)]))
                 pages)
            [[(key/head)
              (codec/encode-head {:revision-id (:revision-id revision)})]]))))

(defn- seed! [store keychain pages]
  (with-open [database    (connection/database store "d-push-integration")
              transaction (connection/writable-transaction database)]
    (ffi/await (ffi/write-values transaction (root-records keychain pages)))
    (ffi/await (ffi/commit-transaction transaction))))

(deftest pushes-through-domain-sqlite-and-slatedb-boundaries
  (when (support/sqlite-available?)
    (let [keychain  (tempel/keychain)
          store     (connection/open {:object-store-url   "memory:///"
                                      :max-open-databases 1})
          evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {})
          capabilities {:snapshot-reader store
                        :revision-writer store
                        :evaluator evaluator}
          database     {:id           "d-push-integration"
                        :display-name "Primary"
                        :keychain     keychain}]
      (try
        (seed! store keychain (sqlite-pages))
        (let [first-revision
              (database/push!
                capabilities
                database
                {:statements [{:sql        "UPDATE t SET value = ? WHERE id = 1"
                               :parameters ["first"]}]})
              second-revision
              (database/push!
                capabilities
                database
                {:statements
                 [{:sql       (str "SELECT CASE WHEN value = ? THEN 1 "
                                "ELSE abs(-9223372036854775808) END FROM t WHERE id = 1")
                   :parameters ["first"]}
                  {:sql        "UPDATE t SET value = ? WHERE id = 1"
                   :parameters ["second"]}]})]
          (is (= "r-root" (:parent first-revision)))
          (is (= (:revision-id first-revision) (:parent second-revision))))
        (finally
          (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)
          (connection/close store))))))
