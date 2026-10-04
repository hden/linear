(ns linear.adapter.crypto.gcp-kms-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [linear.adapter.crypto]
   [linear.adapter.crypto.core :as crypto]
   [linear.adapter.crypto.gcp-kms :as kms]
   [linear.test :refer [catch-ex-data]]
   [linear.usecase.keychain :as keychain])
  (:import
   (com.google.api.gax.grpc GrpcStatusCode)
   (com.google.api.gax.rpc ApiExceptionFactory UnaryCallable)
   (com.google.cloud.kms.v1 DecryptRequest DecryptResponse EncryptRequest EncryptResponse KeyManagementServiceClient)
   (com.google.cloud.kms.v1.stub KeyManagementServiceStub)
   (com.google.protobuf ByteString Int64Value)
   (io.grpc Status$Code)
   (java.util.zip CRC32C)))

(def ^:private key-name "projects/linear/locations/global/keyRings/linear/cryptoKeys/kek")

(defn- checksum [^bytes value]
  (let [crc (CRC32C.)]
    (.update crc value 0 (alength value))
    (Int64Value/of (.getValue crc))))

(defn- api-error [code]
  (ApiExceptionFactory/createException (Exception. "KMS failure")
    (GrpcStatusCode/of code) false))

(defn- callable [f]
  (proxy [UnaryCallable] []
    (call
      ([request] (f request))
      ([request _] (f request)))))

(defn- sdk-client [{:keys [encrypt-response decrypt-response encrypt-error decrypt-error requests closed]}]
  (KeyManagementServiceClient/create
    (proxy [KeyManagementServiceStub] []
      (getOperationsStub [] nil)
      (getHttpJsonOperationsStub [] nil)
      (encryptCallable []
        (callable (fn [request]
                    (when requests (swap! requests conj request))
                    (when encrypt-error (throw encrypt-error))
                    encrypt-response)))
      (decryptCallable []
        (callable (fn [request]
                    (when requests (swap! requests conj request))
                    (when decrypt-error (throw decrypt-error))
                    decrypt-response)))
      (close [] (when closed (swap! closed inc))))))

(defn- encrypt-response [ciphertext]
  (-> (EncryptResponse/newBuilder)
      (.setName (str key-name "/cryptoKeyVersions/1"))
      (.setCiphertext (ByteString/copyFrom ^bytes ciphertext))
      (.setCiphertextCrc32C (checksum ciphertext))
      (.setVerifiedPlaintextCrc32C true)
      (.setVerifiedAdditionalAuthenticatedDataCrc32C true)
      .build))

(defn- decrypt-response [plaintext]
  (-> (DecryptResponse/newBuilder)
      (.setPlaintext (ByteString/copyFrom ^bytes plaintext))
      (.setPlaintextCrc32C (checksum plaintext))
      .build))

(defn- key-protection [client]
  (kms/key-service {:client client :key-id key-name}))

(deftest sdk-requests-bind-keychain-to-vault
  (let [dek (crypto/new-keychain)
        frozen (crypto/freeze dek)
        ciphertext (byte-array [1 2 3])
        aad (.getBytes "v-first" "UTF-8")
        requests (atom [])]
    (with-open [client (sdk-client {:requests requests
                                    :encrypt-response (encrypt-response ciphertext)
                                    :decrypt-response (decrypt-response frozen)})]
      (let [kek (key-protection client)
            wrapped (keychain/wrap kek {:keychain dek :associated-data aad})
            restored (keychain/unwrap kek {:ciphertext wrapped :associated-data aad})
            page (keychain/encrypt dek {:value (.getBytes "page" "UTF-8")})
            ^EncryptRequest encryption (first @requests)
            ^DecryptRequest decryption (second @requests)]
        (is (= [1 2 3] (vec wrapped)))
        (is (= "page" (String. ^bytes (keychain/decrypt restored {:ciphertext page}) "UTF-8")))
        (is (keychain/keychain? restored))
        (is (= key-name (keychain/id kek)))
        (is (keychain/keychain? (keychain/generate kek)))
        (is (= (seq frozen) (seq (.toByteArray (.getPlaintext encryption)))))
        (is (= (checksum frozen) (.getPlaintextCrc32C encryption)))
        (is (= (seq ciphertext) (seq (.toByteArray (.getCiphertext decryption)))))
        (is (= (checksum ciphertext) (.getCiphertextCrc32C decryption)))
        (doseq [request @requests]
          (is (= key-name (.getName request)))
          (is (= (seq aad) (seq (.toByteArray (.getAdditionalAuthenticatedData request)))))
          (is (= (checksum aad) (.getAdditionalAuthenticatedDataCrc32C request))))))))

(deftest sdk-errors-preserve-decryption-contract
  (doseq [[operation error-key code expected]
          [[:decrypt :decrypt-error Status$Code/INVALID_ARGUMENT nil]
           [:encrypt :encrypt-error Status$Code/INVALID_ARGUMENT ::keychain/key-service-failed]
           [:encrypt :encrypt-error Status$Code/PERMISSION_DENIED ::keychain/key-service-failed]
           [:decrypt :decrypt-error Status$Code/UNAVAILABLE ::keychain/key-service-failed]]]
    (testing (str operation " " code)
      (with-open [client (sdk-client {error-key (api-error code)})]
        (let [result (catch-ex-data #(case operation
                                       :encrypt (keychain/wrap (key-protection client) {:keychain (crypto/new-keychain)})
                                       :decrypt (keychain/unwrap (key-protection client) {:ciphertext (byte-array [1])})))]
          (if expected
            (do (is (= expected (:reason result)))
                (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category result))))
            (is (nil? result))))))))

(deftest unexpected-sdk-errors-propagate
  (let [error (IllegalStateException. "Unexpected SDK failure")]
    (doseq [[operation error-key] [[:encrypt :encrypt-error] [:decrypt :decrypt-error]]]
      (with-open [client (sdk-client {error-key error})]
        (is (identical? error
              (try (case operation
                     :encrypt (keychain/wrap (key-protection client) {:keychain (crypto/new-keychain)})
                     :decrypt (keychain/unwrap (key-protection client) {:ciphertext (byte-array [1])}))
                   (catch IllegalStateException caught caught))))))))

(deftest corrupt-kms-responses-are-service-failures
  (let [encrypted (encrypt-response (byte-array [1 2 3]))
        decrypted (decrypt-response (crypto/freeze (crypto/new-keychain)))]
    (doseq [[label response]
            [["plaintext verification absent" (-> encrypted .toBuilder .clearVerifiedPlaintextCrc32C .build)]
             ["AAD verification absent" (-> encrypted .toBuilder .clearVerifiedAdditionalAuthenticatedDataCrc32C .build)]
             ["ciphertext CRC absent" (-> encrypted .toBuilder .clearCiphertextCrc32C .build)]
             ["ciphertext CRC mismatch" (-> encrypted .toBuilder (.setCiphertextCrc32C (Int64Value/of 0)) .build)]
             ["different key" (-> encrypted .toBuilder (.setName (str key-name "-other/cryptoKeyVersions/1")) .build)]
             ["plaintext CRC absent" (-> decrypted .toBuilder .clearPlaintextCrc32C .build)]
             ["plaintext CRC mismatch" (-> decrypted .toBuilder (.setPlaintextCrc32C (Int64Value/of 0)) .build)]]]
      (testing label
        (with-open [client (sdk-client {:encrypt-response response :decrypt-response response})]
          (let [data (catch-ex-data #(if (instance? EncryptResponse response)
                                       (keychain/wrap (key-protection client) {:keychain (crypto/new-keychain)})
                                       (keychain/unwrap (key-protection client) {:ciphertext (byte-array [1])})))]
            (is (= ::keychain/key-service-failed (:reason data)))
            (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category data)))))))))

(deftest invalid-key-names-fail-before-opening-a-key-service
  (doseq [name ["" "invalid" "projects/p/locations/l/keyRings/r"]]
    (is (thrown? IllegalArgumentException (kms/open {:key-id name})))
    (is (thrown? IllegalArgumentException
          (ig/init-key :linear.adapter.crypto/key-service
                       {:deployment-target "gcp" :gcp-kms-key-name name})))))

(deftest halting-the-key-service-closes-its-owned-client
  (let [closed (atom 0)
        service (key-protection (sdk-client {:closed closed}))]
    (ig/halt-key! :linear.adapter.crypto/key-service service)
    (is (= 1 @closed))))
