(ns linear.usecase.database-integration-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.store]
   [linear.adapter.sqlite.evaluator]
   [linear.adapter.sqlite.test-support :as support]
   [linear.test :refer [run]]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database]
   [linear.usecase.keychain :as keychain]
   [next.jdbc :as jdbc]
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

(defn- seed! [store database-id keychain pages]
  (with-open [database    (connection/database store database-id)
              transaction (connection/writable-transaction database)]
    (ffi/await (ffi/write-values transaction (root-records keychain pages)))
    (ffi/await (ffi/commit-transaction transaction))))

(defn- seed-database! [datasource master-key keychain]
  (let [suffix        (random-uuid)
        database-id   (str "d-" suffix)
        vault-id      (str "v-" suffix)
        transaction-id (str "tx-" suffix)
        attributes-id (str "a-" suffix)]
    (jdbc/with-transaction [tx datasource]
      (jdbc/execute! tx ["INSERT INTO transactions (id) VALUES (?)" transaction-id])
      (jdbc/execute! tx
                     ["INSERT INTO vaults (id, owner, ciphertext, encrypted_by, created_by) VALUES (?, ?, ?, ?, ?)"
                      vault-id
                      "owner-1"
                      (keychain/encrypt master-key keychain
                                        {:associated-data (.getBytes ^String vault-id "UTF-8")})
                      "dev-ephemeral"
                      transaction-id])
      (jdbc/execute! tx
                     ["INSERT INTO databases (id, encrypted_by, current_attributes) VALUES (?, ?, ?)"
                      database-id vault-id attributes-id])
      (jdbc/execute! tx
                     ["INSERT INTO database_attributes (id, database_id, display_name, created_by) VALUES (?, ?, ?, ?)"
                      attributes-id database-id "Primary" transaction-id]))
    database-id))

(deftest pushes-through-domain-sqlite-and-slatedb-boundaries
  (when (support/sqlite-available?)
    (with-system [system (run {:keys [:duct.database/sql
                                      :duct.migrator/ragtime
                                      :linear.adapter.slatedb.store/store
                                      :linear.adapter.sqlite.evaluator/evaluator]})]
      (let [datasource (:duct.database.sql/hikaricp system)
            store      (:linear.adapter.slatedb.store/store system)
            evaluator  (:linear.adapter.sqlite.evaluator/evaluator system)
            master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))
            keychain   (crypto/new-keychain)
            database-id (seed-database! datasource master-key keychain)
            context    {::core/database datasource
                        ::core/master-key master-key
                        ::core/revision-store store
                        ::core/evaluator evaluator}]
        (seed! store database-id keychain (sqlite-pages))
        (let [first-revision
              (database/push!
                context
                database-id
                {:statements [{:sql        "UPDATE t SET value = ? WHERE id = 1"
                               :parameters ["first"]}]})
              second-revision
              (database/push!
                context
                database-id
                {:statements
                 [{:sql       (str "SELECT CASE WHEN value = ? THEN 1 "
                                "ELSE abs(-9223372036854775808) END FROM t WHERE id = 1")
                   :parameters ["first"]}
                  {:sql        "UPDATE t SET value = ? WHERE id = 1"
                   :parameters ["second"]}]})]
          (is (= "r-root" (:parent first-revision)))
          (is (= (:revision-id first-revision) (:parent second-revision))))))))
