(ns linear.test-data.postgres
  (:require
   [linear.adapter.crypto.tempel :as crypto]
   [linear.usecase.keychain :as keychain]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel]))

(defn create-database!
  [{:keys [datasource master-key keychain database-id vault-id transaction-id attributes-id]
    :or   {master-key     (crypto/keychain "dev-ephemeral" (tempel/keychain))
           keychain       (crypto/new-keychain)
           database-id    (str "d-" (random-uuid))
           vault-id       (str "v-" (random-uuid))
           transaction-id (str "tx-" (random-uuid))
           attributes-id  (str "a-" (random-uuid))}}]
  (let [ciphertext (keychain/encrypt master-key keychain
                                     {:associated-data (.getBytes ^String vault-id "UTF-8")})]
    (jdbc/with-transaction [tx datasource]
      (jdbc/execute! tx ["INSERT INTO transactions (id) VALUES (?)" transaction-id])
      (jdbc/execute! tx
                     ["INSERT INTO vaults (id, owner, ciphertext, encrypted_by, created_by) VALUES (?, ?, ?, ?, ?)"
                      vault-id "owner-1" ciphertext "dev-ephemeral" transaction-id])
      (jdbc/execute! tx
                     ["INSERT INTO databases (id, encrypted_by, current_attributes) VALUES (?, ?, ?)"
                      database-id vault-id attributes-id])
      (jdbc/execute! tx
                     ["INSERT INTO database_attributes (id, database_id, display_name, created_by) VALUES (?, ?, ?, ?)"
                      attributes-id database-id "Primary" transaction-id]))
    {:database-id database-id
     :vault-id vault-id
     :transaction-id transaction-id
     :tx-id transaction-id
     :attributes-id attributes-id
     :master-key master-key
     :keychain keychain}))

(defn tombstone-database! [{:keys [datasource database-id transaction-id]}]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx
                   ["UPDATE databases SET current_attributes = NULL WHERE id = ?"
                    database-id])
    (jdbc/execute! tx
                   ["INSERT INTO database_tombstones (id, database_id, created_by) VALUES (?, ?, ?)"
                    (str "t-" (random-uuid)) database-id transaction-id])))
