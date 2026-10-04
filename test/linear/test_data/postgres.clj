(ns linear.test-data.postgres
  (:require
   [linear.adapter.crypto.core :as crypto-core]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.usecase.keychain :as keychain]
   [next.jdbc :as jdbc]))

(defn create-database!
  [{:keys [datasource key-protection keychain database-id vault-id transaction-id attributes-id actor]
    :or   {key-protection     (crypto/open {:key-id "dev-ephemeral"})
           keychain       (crypto-core/new-keychain)
           database-id    (str "d-" (random-uuid))
           vault-id       (str "v-" (random-uuid))
           transaction-id (str "tx-" (random-uuid))
           attributes-id  (str "a-" (random-uuid))
           actor          "actor-1"}}]
  (let [ciphertext (keychain/wrap key-protection {:associated-data (.getBytes ^String vault-id "UTF-8")
                                                  :keychain keychain})]
    (jdbc/with-transaction [tx datasource]
      (jdbc/execute! tx ["INSERT INTO transactions (id, actor) VALUES (?, ?)" transaction-id actor])
      (jdbc/execute! tx
                     ["INSERT INTO vaults (id, ciphertext, encrypted_by, created_by) VALUES (?, ?, ?, ?)"
                      vault-id ciphertext (keychain/id key-protection) transaction-id])
      (jdbc/execute! tx
                     ["INSERT INTO vault_grants (vault_id, subject, permission) VALUES (?, ?, ?)"
                      vault-id actor "manage"])
      (jdbc/execute! tx
                     ["INSERT INTO databases (id, encrypted_by, current_attributes) VALUES (?, ?, ?)"
                      database-id vault-id attributes-id])
      (jdbc/execute! tx
                     ["INSERT INTO database_attributes (id, database_id, display_name, created_by) VALUES (?, ?, ?, ?)"
                      attributes-id database-id "Primary" transaction-id]))
    {:database-id database-id
     :vault-id vault-id
     :transaction-id transaction-id
     :attributes-id attributes-id
     :key-protection key-protection
     :keychain keychain
     :actor actor}))
