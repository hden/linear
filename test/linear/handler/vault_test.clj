(ns linear.handler.vault-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.handler.vault :as handler]
   [linear.test :refer [run]]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction]
   [ring.mock.request :refer [header request]]
   [taoensso.tempel :as tempel]))

(defn- vault-context [datasource]
  {::core/database   datasource
   ::core/keychain   crypto/new-keychain
   ::core/master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))})

(deftest create-handler-rejects-a-missing-idempotency-header
  (is (= 400
         (:status ((handler/create {})
                   (request :post "/control/v1/vaults"))))))

(deftest create-handler-translates-backend-failure-to-500
  (let [database (reify transaction/Transactable
                   (-transact [_ _ _]
                     (throw (ex-info "backend failed"
                                     {:cognitect.anomalies/category :cognitect.anomalies/fault}))))
        response ((handler/create {::core/database database})
                  (header (request :post "/control/v1/vaults")
                          "idempotency-key" "backend-failure"))]
    (is (= 500 (:status response)))))

(deftest ^:integration create-handler-returns-201-and-replays-an-idempotent-request
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [context  (vault-context (:duct.database.sql/hikaricp system))
          create   (handler/create context)
          request  (header (request :post "/control/v1/vaults")
                           "idempotency-key" (str "vault-handler-" (random-uuid)))
          created  (create request)
          replayed (create request)]
      (is (= 201 (:status created)))
      (is (string? (get-in created [:body :id])))
      (is (= (:body created) (:body replayed))))))
