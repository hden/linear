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

(defn- vault-context [{:keys [database]}]
  {::core/database   database
   ::core/keychain   crypto/new-keychain
   ::core/master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))})

(defn- actor-request [request]
  (assoc request :identity {:sub "auth0|vault-handler"}))

(deftest create-handler-rejects-a-missing-idempotency-header
  (is (= 400
         (:status ((handler/create {})
                   (actor-request (request :post "/control/v1/vaults")))))))

(deftest ^:integration create-handler-translates-backend-failure-to-500
  (let [database (reify transaction/Transactable
                   (-transact [_ _ _]
                     (throw (ex-info "distinctive backend failure"
                                     {:cognitect.anomalies/category :cognitect.anomalies/fault}))))
        response ((handler/create (vault-context {:database database}))
                  (actor-request
                    (header (request :post "/control/v1/vaults")
                            "idempotency-key" "backend-failure")))]
    (is (= 500 (:status response)))
    (is (= {:error "distinctive backend failure"} (:body response)))))

(deftest ^:integration create-handler-returns-201
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [context  (vault-context {:database (:duct.database.sql/hikaricp system)})
          create   (handler/create context)
          request  (actor-request
                     (header (request :post "/control/v1/vaults")
                             "idempotency-key" (str "vault-handler-" (random-uuid))))
          created  (create request)]
      (is (= 201 (:status created)))
      (is (string? (get-in created [:body :id]))))))
