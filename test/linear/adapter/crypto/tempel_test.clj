(ns linear.adapter.crypto.tempel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [linear.adapter.crypto]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.test-data.tempel :as data]
   [linear.usecase.keychain :as keychain]
   [taoensso.tempel :as tempel]))

(deftest wrapping-and-unwrapping-a-vault-key-retains-access-to-encrypted-data
  (with-open [service (ig/init-key :linear.adapter.crypto/key-service
                        {:provider :tempel :key-id "dev-ephemeral"})]
    (let [data-key (keychain/generate service)
          options {:associated-data (.getBytes "v-primary" "UTF-8")}
          ciphertext (keychain/wrap service (assoc options :keychain data-key))
          unwrapped (keychain/unwrap service (assoc options :ciphertext ciphertext))
          plaintext (.getBytes "secret" "UTF-8")
          encrypted (keychain/encrypt data-key {:value plaintext})]
      (is (= "dev-ephemeral" (keychain/id service)))
      (is (keychain/keychain? unwrapped))
      (is (= (seq plaintext) (seq (keychain/decrypt unwrapped {:ciphertext encrypted}))))
      (testing "a wrapped key belongs to its vault"
        (is (nil? (keychain/unwrap service {:associated-data (.getBytes "v-other" "UTF-8") :ciphertext ciphertext}))))
      (testing "another service cannot unwrap it"
        (with-open [other (crypto/open {:key-id "dev-ephemeral"})]
          (is (nil? (keychain/unwrap other (assoc options :ciphertext ciphertext)))))))))

(deftest malformed-ciphertext-cannot-be-unwrapped
  (with-open [service (crypto/open {:key-id "master"})]
    (let [wrapped (keychain/wrap service {:keychain (keychain/generate service)})]
      (doseq [[description input] [["empty input" (byte-array 0)]
                                   ["not a Tempel envelope" (byte-array [1 2 3])]
                                   ["truncated header" (java.util.Arrays/copyOf wrapped 4)]
                                   ["truncated encrypted key" (java.util.Arrays/copyOf wrapped (dec (alength wrapped)))]]]
        (testing description
          (is (nil? (keychain/unwrap service {:ciphertext input}))))))))

(deftest malformed-keychain-envelopes-cannot-be-unwrapped
  (with-open [service (crypto/open {:key-id "master"})]
    (doseq [[description ciphertext] (data/malformed-envelopes {:key-protection service})]
      (testing description
        (is (nil? (keychain/unwrap service {:ciphertext ciphertext})))))))

(deftest a-data-envelope-is-not-a-wrapped-key
  (with-open [service (crypto/open {:key-id "master"})]
    (is (nil? (keychain/unwrap service
                {:ciphertext (tempel/encrypt-with-symmetric-key (.getBytes "not a key" "UTF-8") (tempel/keychain))})))))
