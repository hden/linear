(ns linear.usecase.vault
  (:require
   [cognitect.anomalies :as anomaly]
   [hden.ulid :refer [ulid]]
   [labrador.core :as lab]
   [linear.usecase.core :as core]
   [linear.usecase.grant :as grant]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.transaction :as transaction]
   [urania.core :as u]))

(defprotocol Database
  (-create! [tx arg-map]))

(defn vault-id []
  (str "v-" (ulid)))

(defn retriever [id]
  (u/mapcat
    (fn [{:keys [ciphertext encrypted-by] :as vault}]
      (u/mapcat
        (fn [configured]
          (when-not configured
            (throw (ex-info "Master key is not configured"
                            {::anomaly/category ::anomaly/fault
                             :reason ::master-key-not-configured
                             :master-key-id encrypted-by})))
          (if-let [keychain (keychain/decrypt configured {:associated-data (.getBytes ^String id "UTF-8")
                                                          :ciphertext ciphertext})]
            (lab/traverse
              (-> vault
                  (assoc :keychain keychain)
                  (dissoc :ciphertext :encrypted-by)))
            (throw (ex-info "Vault keychain could not be decrypted"
                            {::anomaly/category ::anomaly/fault
                             :reason ::vault-decryption-failed
                             :vault-id id}))))
        (lab/fetch ::keychain/master-key encrypted-by)))
    (lab/fetch ::vault id)))

(defn- fetch [master-key tx ids]
  (u/run!! (lab/traverse (into {}
                               (map (fn [id]
                                      [id (retriever id)]))
                               ids))
           {:env {:tx tx
                  ::core/master-key master-key}}))

(defn create!
  {:malli/schema [:->
                  :map
                  [:map
                   [:data [:sequential [:map]]]
                   [:idempotency-key :string]
                   [:actor :string]]
                  [:vector :string]]}
  [context {:keys [actor data idempotency-key]}]
  (let [master-key (core/master-key context)
        vaults     (mapv (fn [attributes]
                           (let [id       (vault-id)
                                 keychain (core/keychain context)]
                             (assoc attributes
                                    :id id
                                    :ciphertext (keychain/encrypt master-key {:associated-data (.getBytes ^String id "UTF-8")
                                                                              :value keychain})
                                    :encrypted-by (keychain/id master-key))))
                         data)]
    (transaction/with-transaction [tx (core/transactable context)]
      (let [{:keys [ids created?]} (-create! tx {:actor actor
                                                 :data vaults
                                                 :idempotency-key idempotency-key})
            ids (vec ids)]
        (when created?
          (grant/-set-grants! tx {:data (mapv (fn [vault-id]
                                                {:vault-id vault-id
                                                 :subject actor
                                                 :permission :manage})
                                              ids)}))
        ids))))

(defn get-by-ids
  {:malli/schema [:->
                  :map
                  [:map
                   [:actor :string]
                   [:ids [:sequential :string]]]
                  [:map-of :string :map]]}
  [context {:keys [actor ids]}]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    (doseq [id ids]
      (grant/require-permission tx {:actor actor
                                    :vault-id id
                                    :permission :pull}))
    (fetch (core/master-key context) tx ids)))
