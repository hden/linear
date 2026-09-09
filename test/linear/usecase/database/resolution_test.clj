(ns linear.usecase.database.resolution-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.test :refer [run]]
   [linear.test-data.postgres :as postgres-data]
   [linear.usecase.database :as database]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.vault :as vault]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel]))

(deftest ^:integration resolve-by-id-treats-missing-and-tombstoned-databases-as-not-found
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key transaction-id]} (postgres-data/create-database! {:datasource datasource})
          missing-id (str "d-" (random-uuid))
          resolve-error
          (fn [id]
            (try
              (jdbc/with-transaction [tx datasource {:read-only true}]
                (database/resolve-by-id master-key tx id))
              nil
              (catch clojure.lang.ExceptionInfo error error)))]
      (let [{:keys [tombstone-id]}
            (postgres-data/tombstone-database! {:datasource datasource
                                                :database-id database-id
                                                :transaction-id transaction-id})]
        (is (= tombstone-id
               (:database_tombstones/id
                 (first (jdbc/execute! datasource
                                       ["SELECT id FROM database_tombstones WHERE id = ?"
                                        tombstone-id]))))))
      (doseq [id [missing-id database-id]]
        (let [error (resolve-error id)]
          (is (= ::anomaly/not-found (-> error ex-data ::anomaly/category)))
          (is (= ::database/database-not-found (-> error ex-data :reason)))
          (is (= id (-> error ex-data :database-id))))))))

(deftest ^:integration resolve-by-id-supports-a-supplied-master-key-id
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          master-key (crypto/keychain "test-master-key" (tempel/keychain))
          {:keys [database-id vault-id]}
          (postgres-data/create-database! {:datasource datasource
                                           :master-key master-key})
          resolved (jdbc/with-transaction [tx datasource {:read-only true}]
                     (database/resolve-by-id master-key tx database-id))]
      (is (= database-id (:id resolved)))
      (is (= vault-id (get-in resolved [:vault :id])))
      (is (keychain/keychain? (get-in resolved [:vault :keychain]))))))

(deftest ^:integration database-resolution-preserves-key-resolution-errors
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id vault-id]} (postgres-data/create-database! {:datasource datasource})]
      (doseq [[configured expected]
              [[nil {:reason ::vault/master-key-not-configured
                     :master-key-id "dev-ephemeral"}]
               [(crypto/keychain "another-key" (tempel/keychain))
                {:reason ::vault/master-key-not-configured
                 :master-key-id "dev-ephemeral"}]
               [(crypto/keychain "dev-ephemeral" (tempel/keychain))
                {:reason ::vault/vault-decryption-failed
                 :vault-id vault-id}]]]
        (let [error (try
                      (jdbc/with-transaction [tx datasource {:read-only true}]
                        (database/resolve-by-id configured tx database-id))
                      nil
                      (catch Exception error error))
              data (some #(when (:reason (ex-data %)) (ex-data %))
                         (take-while some? (iterate ex-cause error)))]
          (is (= (assoc expected ::anomaly/category ::anomaly/fault) data)))))))
