(ns linear.usecase.grant-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.test :refer [run]]
   [linear.usecase.core :as core]
   [linear.usecase.grant :as grant]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]
   [taoensso.tempel :as tempel]))

(deftest ^:integration grants-are-managed-within-the-vault-policy
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [context {::core/database   (:duct.database.sql/hikaricp system)
                   ::core/keychain   crypto/new-keychain
                   ::core/master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))}
          ids     (sort (vault/create! context {:actor "actor-1"
                                                :data [{} {}]
                                                :idempotency-key (str (random-uuid))}))
          id      (first ids)]
      (grant/set-grant! context {:actor "actor-1"
                                 :vault-id id
                                 :subject "actor-2"
                                 :permission :pull})
      (is (= [{:subject "actor-1" :permission :manage}
              {:subject "actor-2" :permission :pull}]
             (grant/list-grants context {:actor "actor-1" :vault-id id})))
      (grant/revoke-grant! context {:actor "actor-1"
                                    :vault-id id
                                    :subject "actor-2"})
      (is (= [{:subject "actor-1" :permission :manage}]
             (grant/list-grants context {:actor "actor-1" :vault-id id})))
      (transaction/with-transaction [tx (core/transactable context)]
        (grant/-set-grants! tx {:data (mapv (fn [vault-id]
                                              {:vault-id vault-id
                                               :subject "actor-2"
                                               :permission :pull})
                                            ids)}))
      (is (every? (fn [vault-id]
                    (some #(= {:subject "actor-2" :permission :pull} %)
                          (grant/list-grants context {:actor "actor-1"
                                                      :vault-id vault-id})))
                  ids))
      (transaction/with-transaction [tx (core/transactable context)]
        (grant/-revoke-grants! tx {:data (mapv (fn [vault-id]
                                                 {:vault-id vault-id :subject "actor-2"})
                                               ids)}))
      (is (every? #(= [{:subject "actor-1" :permission :manage}]
                     (grant/list-grants context {:actor "actor-1" :vault-id %}))
                  ids)))))

(deftest ^:integration batch-revoke-retains-non-target-cross-pairs
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [context {::core/database   (:duct.database.sql/hikaricp system)
                   ::core/keychain   crypto/new-keychain
                   ::core/master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))}
          [first-id second-id]
          (sort (vault/create! context {:actor "actor-1"
                                        :data [{} {}]
                                        :idempotency-key (str (random-uuid))}))]
      (transaction/with-transaction [tx (core/transactable context)]
        (grant/-set-grants! tx {:data [{:vault-id first-id
                                        :subject "actor-2"
                                        :permission :pull}
                                       {:vault-id first-id
                                        :subject "actor-3"
                                        :permission :pull}
                                       {:vault-id second-id
                                        :subject "actor-2"
                                        :permission :pull}
                                       {:vault-id second-id
                                        :subject "actor-3"
                                        :permission :pull}]})
        (grant/-revoke-grants! tx {:data [{:vault-id first-id
                                           :subject "actor-2"}
                                          {:vault-id second-id
                                           :subject "actor-3"}]}))
      (is (= [{:subject "actor-1" :permission :manage}
              {:subject "actor-3" :permission :pull}]
             (grant/list-grants context {:actor "actor-1" :vault-id first-id})))
      (is (= [{:subject "actor-1" :permission :manage}
              {:subject "actor-2" :permission :pull}]
             (grant/list-grants context {:actor "actor-1" :vault-id second-id}))))))
