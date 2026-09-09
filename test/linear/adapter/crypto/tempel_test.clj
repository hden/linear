(ns linear.adapter.crypto.tempel-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.usecase.keychain :as keychain]
   [taoensso.tempel :as tempel]))

(deftest ^:integration wraps-and-unwraps-a-keychain-for-one-vault
  (let [master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))
        keychain   (crypto/new-keychain)
        options    {:associated-data (.getBytes "v-primary" "UTF-8")}
        ciphertext (keychain/encrypt master-key keychain options)
        unwrapped  (keychain/decrypt master-key ciphertext options)
        plaintext  (.getBytes "secret" "UTF-8")
        encrypted  (keychain/encrypt unwrapped plaintext)]
    (is (= "dev-ephemeral" (keychain/id master-key)))
    (is (keychain/keychain? unwrapped))
    (is (= (seq plaintext)
           (seq (keychain/decrypt unwrapped encrypted))))
    (is (nil? (keychain/decrypt master-key ciphertext
                                {:associated-data (.getBytes "v-other" "UTF-8")})))))
