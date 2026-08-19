(ns linear.adapter.database.impl.postgres-test
  (:require
   [clj-ulid :refer [ulid]]
   [clojure.test :refer [deftest is]]
   [duct.test :as duct-test]
   [linear.adapter.database.core :as database]
   [linear.adapter.database.impl.postgres]
   [linear.test :refer [run]]))

(defn- jdbc-url []
  (or (System/getenv "JDBC_DATABASE_URL")
      "jdbc:postgresql://localhost:5432/linear?user=postgres&password=postgres"))

(deftest query-and-transact-use-the-postgres-adapter
  (duct-test/with-system [system (run {:config {:system {:duct.module/logging {}
                                                         :duct.module/sql {}}}
                                       :vars {'jdbc-url (jdbc-url)}})]
    (let [datasource (:duct.database.sql/hikaricp system)
          id (str "tx-" (ulid))]
      (database/transact!
        datasource
        {:statements [{:statement {:insert-into :transactions
                                   :columns [:id :actor]
                                   :values [[id "Linear"]]}
                       :parse-fn identity}]
         :timeout-ms 2000
         :tx-id "postgres-adapter-test"})
      (is (= [{:id id :actor "Linear"}]
             (database/query
               datasource
               {:statement {:select [:id :actor]
                            :from [:transactions]
                            :where [:= :id id]}
                :parse-fn identity
                :timeout-ms 2000}))))))
