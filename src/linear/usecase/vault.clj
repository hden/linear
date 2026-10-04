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

(defn- require-key-protection [protection encrypted-by]
  (when-not (and protection (= encrypted-by (keychain/id protection)))
    (throw (ex-info "Master key is not configured"
                    {::anomaly/category ::anomaly/fault
                     :reason ::master-key-not-configured
                     :master-key-id encrypted-by}))))

(defn retriever [id {:keys [key-protection]}]
  (u/mapcat
    (fn [{:keys [ciphertext encrypted-by] :as vault}]
      (require-active vault)
      (require-key-protection key-protection encrypted-by)
      (if-let [data-key (keychain/unwrap key-protection
                          {:associated-data (.getBytes ^String id "UTF-8")
                           :ciphertext ciphertext})]
        (lab/traverse
          (-> vault
              (assoc :keychain data-key)
              (dissoc :ciphertext :encrypted-by)))
        (throw (ex-info "Vault keychain could not be decrypted"
                        {::anomaly/category ::anomaly/fault
                         :reason ::vault-decryption-failed
                         :vault-id id}))))
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
  [tx {:keys [vault-id key-protection]}]
  (run-retriever (retriever vault-id {:key-protection key-protection}) {:tx tx}))

(defn- fetch [key-protection tx ids]
  (run-retriever (lab/traverse (into {}
                                 (map (fn [id]
                                        [id (retriever id {:key-protection key-protection})]))
                                 ids))
    {:tx tx}))

(defn create!
  {:malli/schema [:->
                  :map
                  [:map
                   [:data [:sequential [:map]]]
                   [:idempotency-key :string]
                   [:actor :string]]
                  [:vector :string]]}
  [context {:keys [actor data idempotency-key]}]
  (let [key-protection (core/key-protection context)
        vaults     (mapv (fn [attributes]
                           (let [id       (vault-id)
                                 keychain (keychain/generate (core/key-generator context))]
                             (assoc attributes
                                    :id id
                                    :ciphertext (keychain/wrap key-protection {:associated-data (.getBytes ^String id "UTF-8")
                                                                               :keychain keychain})
                                    :encrypted-by (keychain/id key-protection))))
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
    (fetch (core/key-protection context) tx ids)))

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
          key-protection (core/key-protection context)]
      (require-key-protection key-protection encrypted-by)
      (when-not (keychain/unwrap key-protection
                  {:associated-data (.getBytes ^String vault-id "UTF-8")
                   :ciphertext ciphertext})
        (throw (invalid-token)))
      (when-not (:ciphertext stored)
        (-store-key! tx {:vault-id vault-id :encrypted-by encrypted-by :ciphertext ciphertext}))
      nil)))
