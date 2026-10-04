(ns linear.adapter.crypto.gcp-kms
  (:require
   [clojure.string :as string]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.crypto.core :as crypto]
   [linear.usecase.keychain :as keychain])
  (:import
   (com.google.api.gax.rpc ApiException InvalidArgumentException)
   (com.google.cloud.kms.v1 CryptoKeyName DecryptRequest DecryptResponse EncryptRequest EncryptResponse KeyManagementServiceClient)
   (com.google.protobuf ByteString Int64Value)
   (java.io Closeable)
   (java.util.zip CRC32C)))

(defn- checksum ^Int64Value [^bytes value]
  (let [crc (CRC32C.)]
    (.update crc value 0 (alength value))
    (Int64Value/of (.getValue crc))))

(defn- key-service-failed [cause]
  (ex-info "Key service failed"
           {::anomaly/category ::anomaly/fault
            :reason ::keychain/key-service-failed}
           cause))

(defn- authenticated-data [value]
  (or value (byte-array 0)))

(defn- encrypt-request [key-name plaintext aad]
  (-> (EncryptRequest/newBuilder)
      (.setName ^String key-name)
      (.setPlaintext (ByteString/copyFrom ^bytes plaintext))
      (.setPlaintextCrc32C (checksum plaintext))
      (.setAdditionalAuthenticatedData (ByteString/copyFrom ^bytes aad))
      (.setAdditionalAuthenticatedDataCrc32C (checksum aad))
      .build))

(defn- decrypt-request [key-name ciphertext aad]
  (-> (DecryptRequest/newBuilder)
      (.setName ^String key-name)
      (.setCiphertext (ByteString/copyFrom ^bytes ciphertext))
      (.setCiphertextCrc32C (checksum ciphertext))
      (.setAdditionalAuthenticatedData (ByteString/copyFrom ^bytes aad))
      (.setAdditionalAuthenticatedDataCrc32C (checksum aad))
      .build))

(defn- verified-ciphertext [key-name ^EncryptResponse response]
  (let [ciphertext (.toByteArray (.getCiphertext response))]
    (when-not (and (.getVerifiedPlaintextCrc32C response)
                   (.getVerifiedAdditionalAuthenticatedDataCrc32C response)
                   (.hasCiphertextCrc32C response)
                   (= (checksum ciphertext) (.getCiphertextCrc32C response))
                   (string/starts-with? (.getName response) (str key-name "/cryptoKeyVersions/")))
      (throw (key-service-failed nil)))
    ciphertext))

(defn- verified-plaintext [^DecryptResponse response]
  (let [plaintext (.toByteArray (.getPlaintext response))]
    (when-not (and (.hasPlaintextCrc32C response)
                   (= (checksum plaintext) (.getPlaintextCrc32C response)))
      (throw (key-service-failed nil)))
    plaintext))

(defn- wrap-keychain [^KeyManagementServiceClient client key-name value associated-data]
  (let [request (encrypt-request key-name (crypto/freeze value) (authenticated-data associated-data))
        response (try
                   (.encrypt client ^EncryptRequest request)
                   (catch ApiException cause
                     (throw (key-service-failed cause))))]
    (verified-ciphertext key-name response)))

(defn- unwrap-keychain [^KeyManagementServiceClient client key-name ciphertext associated-data]
  (let [request (decrypt-request key-name ciphertext (authenticated-data associated-data))
        response (try
                   (.decrypt client ^DecryptRequest request)
                   (catch InvalidArgumentException _ nil)
                   (catch ApiException cause
                     (throw (key-service-failed cause))))]
    (when response
      (crypto/thaw (verified-plaintext response)))))

(defrecord ^:private KeyService [client key-id]
  keychain/KeyGenerator
  (-generate [_] (crypto/new-keychain))
  keychain/KeyProtection
  (-id [_] key-id)
  (-wrap [_ data-key {:keys [associated-data]}]
    (wrap-keychain client key-id data-key associated-data))
  (-unwrap [_ ciphertext {:keys [associated-data]}]
    (unwrap-keychain client key-id ciphertext associated-data))
  Closeable
  (close [_] (.close ^KeyManagementServiceClient client)))

(alter-meta! #'->KeyService assoc :private true)
(alter-meta! #'map->KeyService assoc :private true)

(defn key-service
  "Transfers ownership of the client to the returned key service."
  {:malli/schema [:->
                  [:map
                   [:client [:fn #(instance? KeyManagementServiceClient %)]]
                   [:key-id :string]]
                  [:and ::keychain/key-generator ::keychain/key-protection]]}
  [{:keys [client key-id]}]
  (->KeyService client key-id))

(defn open
  {:malli/schema [:-> [:map [:key-id :string]]
                  [:and ::keychain/key-generator ::keychain/key-protection]]}
  [{:keys [key-id]}]
  (when-not (CryptoKeyName/parse key-id)
    (throw (IllegalArgumentException. "KMS key name is required")))
  (key-service {:client (KeyManagementServiceClient/create) :key-id key-id}))
