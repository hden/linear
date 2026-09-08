(ns linear.adapter.postgres.vault-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.test :refer [run]]
   [linear.usecase.core :as core]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.healthcheck :as healthcheck]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.vault :as vault]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel]))

(defn- checkable-evaluator []
  (reify
    healthcheck/Checkable
    (-ready? [_] true)
    (-ok? [_] true)
    evaluator/Evaluator
    (-evaluate [_ _] nil)))

(defn- database-store []
  (reify
    revisions/ConsistentReadable
    (-read-consistently [_ f _] (f nil))
    revisions/RevisionWritable
    (-publish-next! [_ revision _] revision)))

(defn- context [datasource]
  {::core/database       datasource
   ::core/evaluator      (checkable-evaluator)
   ::core/revision-store (database-store)
   ::core/keychain       crypto/new-keychain
   ::core/master-key     (crypto/keychain "dev-ephemeral" (tempel/keychain))})

(deftest vault-creation-is-encrypted-and-idempotent
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp sys)
          context (context datasource)
          key     (str "vault-test-" (random-uuid))
          created (vault/create! context
                    {:data [{:owner "owner-1"}]
                     :idempotency-key key})
          replayed (vault/create! context
                                  {:data [{:owner "owner-1"}]
                                   :idempotency-key key})
          vault   (-> created vals first)]
      (is (= (set (keys created))
             (set (keys replayed))))
      (is (= (first (keys created))
             (:id vault)))
      (is (= "owner-1" (:owner vault)))
      (is (contains? vault :keychain))
      (is (not (contains? vault :ciphertext)))
      (is (not (contains? vault :encrypted-by)))
      (let [stored (first (jdbc/execute! datasource
                            ["SELECT ciphertext, encrypted_by FROM vaults WHERE id = ?"
                             (:id vault)]))]
        (is (bytes? (:vaults/ciphertext stored)))
        (is (= (keychain/id (core/master-key context))
               (:vaults/encrypted_by stored))))
      (let [fetched (vault/get-by-ids context {:ids (vec (keys created))})]
        (is (= (update-vals created #(dissoc % :keychain))
               (update-vals fetched #(dissoc % :keychain))))
        (is (every? (comp keychain/keychain? :keychain) (vals fetched)))))))
