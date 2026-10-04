(ns linear.adapter.crypto.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.adapter.crypto.core :as crypto]
   [linear.usecase.keychain :as keychain]
   [taoensso.tempel :as tempel]))

(deftest ciphertext-with-an-unknown-key-id-cannot-be-decrypted
  (let [foreign (tempel/keychain-add-symmetric-key (tempel/keychain) :random {:key-id "foreign"})
        data-key (crypto/new-keychain)
        plaintext (.getBytes "secret" "UTF-8")
        associated-data (.getBytes "v-test" "UTF-8")
        ciphertext (keychain/encrypt foreign {:value plaintext :associated-data associated-data})
        options {:ciphertext ciphertext :associated-data associated-data}]
    (testing "the embedded key ID is available in the originating keychain"
      (is (= (seq plaintext) (seq (keychain/decrypt foreign options)))))
    (testing "an unknown embedded key ID makes the ciphertext invalid for this keychain"
      (is (nil? (keychain/decrypt data-key options))))))

(deftest encrypted-data-is-authenticated-against-its-record
  (let [data-key (crypto/new-keychain)
        ciphertext (keychain/encrypt data-key {:value (.getBytes "secret" "UTF-8")
                                               :associated-data (.getBytes "record-one" "UTF-8")})]
    (is (nil? (keychain/decrypt data-key {:ciphertext ciphertext
                                          :associated-data (.getBytes "record-other" "UTF-8")})))))

(deftest freezing-and-thawing-a-data-key-retains-access-to-encrypted-data
  (let [data-key (crypto/new-keychain)
        ciphertext (keychain/encrypt data-key {:value (.getBytes "retained data" "UTF-8")})
        restored (crypto/thaw (crypto/freeze data-key))]
    (is (keychain/keychain? restored))
    (is (= "retained data" (String. ^bytes (keychain/decrypt restored {:ciphertext ciphertext}) "UTF-8")))))
