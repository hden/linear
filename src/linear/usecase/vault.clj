(ns linear.usecase.vault
  (:require
   [boring.core :as cbor]
   [cognitect.anomalies :as anomaly]
   [hden.ulid :refer [ulid]]
   [labrador.core :as lab]
   [linear.usecase.core :as core]
   [linear.usecase.grant :as grant]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.transaction :as transaction]
   [urania.core :as u])
  (:import
   (java.util Base64)))

(defprotocol Database
  (-create! [tx arg-map])
  (-read [tx arg-map])
  (-store-key! [tx arg-map]))

(defn vault-id []
  (str "v-" (ulid)))

(defn require-active
  [{:keys [id ciphertext]}]
  (when-not ciphertext
    (throw (ex-info "Vault is deleted"
                    {::anomaly/category ::anomaly/conflict
                     :reason ::vault-deleted
                     :vault-id id}))))

(defn- require-master-key [configured encrypted-by]
  (when-not configured
    (throw (ex-info "Master key is not configured"
                    {::anomaly/category ::anomaly/fault
                     :reason ::master-key-not-configured
                     :master-key-id encrypted-by}))))

(defn retriever [id]
  (u/mapcat
    (fn [{:keys [ciphertext encrypted-by] :as vault}]
      (require-active vault)
      (u/mapcat
        (fn [configured]
          (require-master-key configured encrypted-by)
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

(defn- run-retriever [retriever env]
  (try
    (u/run!! retriever {:env env})
    (catch Exception error
      (if-let [domain-error (some #(when (::anomaly/category (ex-data %)) %)
                              (take-while some? (iterate ex-cause error)))]
        (throw domain-error)
        (throw error)))))

(defn resolve-by-id
  [tx {:keys [vault-id master-key]}]
  (run-retriever (retriever vault-id) {:tx tx ::core/master-key master-key}))

(defn- fetch [master-key tx ids]
  (run-retriever (lab/traverse (into {}
                                 (map (fn [id]
                                        [id (retriever id)]))
                                 ids))
    {:tx tx
     ::core/master-key master-key}))

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

(defn- state [vault]
  (assoc (dissoc vault :ciphertext :encrypted-by)
         :state (if (:ciphertext vault) :active :deleted)))

(defn- authorized-vault [tx {:keys [actor vault-id] :as arg-map} permission lock?]
  (grant/require-permission tx {:actor actor :vault-id vault-id :permission permission})
  (or (-read tx (assoc arg-map :lock? lock?))
      (throw (ex-info "Vault not found"
                      {::anomaly/category ::anomaly/not-found
                       :reason ::vault-not-found}))))

(defn get-state
  {:malli/schema [:-> :map [:map [:actor :string] [:vault-id :string]] :map]}
  [context arg-map]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    (state (authorized-vault tx arg-map :pull false))))

(defn recovery-token
  {:malli/schema [:-> :map [:map [:actor :string] [:vault-id :string]] :string]}
  [context arg-map]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    (let [{:keys [id ciphertext encrypted-by]} (authorized-vault tx arg-map :manage false)]
      (require-active {:id id :ciphertext ciphertext})
      (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                       (cbor/encode [1 id encrypted-by ciphertext])))))

(defn delete!
  {:malli/schema [:-> :map [:map [:actor :string] [:vault-id :string]] :nil]}
  [context {:keys [vault-id] :as arg-map}]
  (transaction/with-transaction [tx (core/transactable context)]
    (authorized-vault tx arg-map :manage true)
    (-store-key! tx {:vault-id vault-id :ciphertext nil :encrypted-by nil})
    nil))

(defn- invalid-token []
  (ex-info "Invalid recovery token"
           {::anomaly/category ::anomaly/incorrect :reason ::invalid-recovery-token}))

(defn- decode-token [token vault-id]
  (let [decoded (try
                  (cbor/decode (.decode (Base64/getUrlDecoder) ^String token))
                  (catch Exception _ (throw (invalid-token))))]
    (when-not (and (vector? decoded) (= 4 (count decoded)))
      (throw (invalid-token)))
    (let [[version token-vault-id encrypted-by ciphertext] decoded]
      (when-not (and (= 1 version) (= vault-id token-vault-id)
                     (string? encrypted-by) (bytes? ciphertext))
        (throw (invalid-token)))
      {:encrypted-by encrypted-by :ciphertext ciphertext})))

(defn restore!
  {:malli/schema [:-> :map [:map [:actor :string] [:vault-id :string] [:token :string]] :nil]}
  [context {:keys [vault-id token] :as arg-map}]
  (transaction/with-transaction [tx (core/transactable context)]
    (let [stored (authorized-vault tx arg-map :manage true)
          {:keys [encrypted-by ciphertext]} (decode-token token vault-id)
          configured (u/run!! (lab/fetch ::keychain/master-key encrypted-by)
                              {:env {:tx tx ::core/master-key (core/master-key context)}})]
      (require-master-key configured encrypted-by)
      (let [decrypted (try
                        (keychain/decrypt configured
                                          {:associated-data (.getBytes ^String vault-id "UTF-8")
                                           :ciphertext ciphertext})
                        (catch Exception _ (throw (invalid-token))))]
        (when-not (keychain/keychain? decrypted)
          (throw (invalid-token))))
      (when-not (:ciphertext stored)
        (-store-key! tx {:vault-id vault-id :encrypted-by encrypted-by :ciphertext ciphertext}))
      nil)))
