(ns linear.usecase.vault
  (:require
   [cognitect.anomalies :as anomaly]
   [hden.ulid :refer [ulid]]
   [labrador.core :as lab]
   [linear.usecase.core :as core]
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
          (if-let [keychain (keychain/decrypt configured ciphertext
                              {:associated-data (.getBytes ^String id "UTF-8")})]
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
                   [:idempotency-key :string]]
                  [:map-of :string :map]]}
  [context {:keys [data idempotency-key]}]
  (let [master-key (core/master-key context)
        vaults     (mapv (fn [attributes]
                           (let [id       (vault-id)
                                 keychain (core/keychain context)]
                             (assoc attributes
                                    :id id
                                    :ciphertext (keychain/encrypt
                                                  master-key keychain
                                                  {:associated-data (.getBytes ^String id "UTF-8")})
                                    :encrypted-by (keychain/id master-key))))
                         data)]
    (transaction/with-transaction [tx (core/transactable context)]
      ;; TODO: verify ownership
      (let [ids (-create! tx {:data vaults :idempotency-key idempotency-key})]
        ;; read your writes
        (fetch master-key tx ids)))))

(defn get-by-ids
  {:malli/schema [:->
                  :map
                  [:map
                   [:ids [:sequential :string]]]
                  [:map-of :string :map]]}
  [context {:keys [ids]}]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    ;; TODO: verify ownership
    (fetch (core/master-key context) tx ids)))
