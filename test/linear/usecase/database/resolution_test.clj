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
   [next.jdbc :as jdbc]))

(defn- tombstone-database!
  [{:keys [datasource database-id transaction-id]}]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx
                   ["INSERT INTO database_tombstones (id, database_id, created_by) VALUES (?, ?, ?)"
                    (str "t-" (random-uuid)) database-id transaction-id])))

(deftest ^:integration resolve-by-id-treats-missing-and-tombstoned-databases-as-not-found
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id key-protection transaction-id]} (postgres-data/create-database! {:datasource datasource})
          missing-id (str "d-" (random-uuid))
          resolve-error
          (fn [id]
            (try
              (jdbc/with-transaction [tx datasource {:read-only true}]
                (database/resolve-by-id tx {:key-protection key-protection
                                            :actor "actor-1"
                                            :permission :pull
                                            :database-id id}))
              nil
              (catch clojure.lang.ExceptionInfo error error)))]
      (tombstone-database! {:datasource datasource
                            :database-id database-id
                            :transaction-id transaction-id})
      (doseq [id [missing-id database-id]]
        (let [error (resolve-error id)]
          (is (= ::anomaly/not-found (-> error ex-data ::anomaly/category)))
          (is (= ::database/database-not-found (-> error ex-data :reason)))
          (is (= id (-> error ex-data :database-id))))))))

(deftest ^:integration resolve-by-id-supports-a-supplied-key-protection-id
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          key-protection (crypto/open {:key-id "test-key-protection"})
          {:keys [database-id vault-id]}
          (postgres-data/create-database! {:datasource datasource
                                           :key-protection key-protection})
          resolved (jdbc/with-transaction [tx datasource {:read-only true}]
                     (database/resolve-by-id tx {:key-protection key-protection
                                                 :actor "actor-1"
                                                 :permission :pull
                                                 :database-id database-id}))]
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
               [(crypto/open {:key-id "another-key"})
                {:reason ::vault/master-key-not-configured
                 :master-key-id "dev-ephemeral"}]
               [(crypto/open {:key-id "dev-ephemeral"})
                {:reason ::vault/vault-decryption-failed
                 :vault-id vault-id}]]]
        (let [error (try
                      (jdbc/with-transaction [tx datasource {:read-only true}]
                        (database/resolve-by-id tx {:key-protection configured
                                                    :actor "actor-1"
                                                    :permission :pull
                                                    :database-id database-id}))
                      nil
                      (catch Exception error error))
              data (some #(when (:reason (ex-data %)) (ex-data %))
                         (take-while some? (iterate ex-cause error)))]
          (is (= (assoc expected ::anomaly/category ::anomaly/fault) data)))))))
