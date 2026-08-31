(ns linear.adapter.postgres.vault-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres.vault]
   [linear.protocol :as protocol]
   [linear.test :refer [run]]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database]
   [linear.usecase.vault :as vault]))

(defn- checkable-evaluator []
  (reify
    protocol/Checkable
    (-ready? [_] true)
    (-ok? [_] true)
    database/Evaluator
    (-evaluate [_ _] nil)))

(defn- database-store []
  (reify
    database/SnapshotReader
    (-latest-snapshot [_ _] nil)
    database/RevisionWriter
    (-publish-next-revision! [_ _ revision] revision)))

(deftest vault-creation-is-encrypted-and-idempotent
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [context {::core/postgres-datasource
                   (:duct.database.sql/hikaricp sys)
                   ::core/database-evaluator (checkable-evaluator)
                   ::core/snapshot-reader    (database-store)
                   ::core/revision-writer    (database-store)}
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
      (is (contains? vault :keychain))
      (is (not (contains? vault :ciphertext)))
      (is (= created
             (vault/get-by-ids context {:ids (vec (keys created))}))))))
