(ns linear.test-data.tempel
  (:require
   [linear.usecase.keychain :as keychain]
   [taoensso.tempel :as tempel])
  (:import
   (java.security PublicKey)
   (java.util Collections)))

(defn malformed-envelopes [{:keys [key-protection associated-data]}]
  (let [data-key (tempel/keychain {:asymmetric-keypairs [:rsa-1024]})
        public-key (:key-cnt @(some :key-pub (vals @data-key)))
        encoded (.getEncoded ^PublicKey public-key)
        ciphertext (keychain/wrap key-protection
                     (cond-> {:keychain data-key}
                       associated-data (assoc :associated-data associated-data)))
        offset (Collections/indexOfSubList (vec ciphertext) (vec encoded))
        invalid-key (aclone ^bytes ciphertext)
        invalid-length (aclone ^bytes ciphertext)]
    (assert (pos? offset))
    (assert (< (alength encoded) 253))
    (aset-byte invalid-key offset (byte 0))
    (aset-byte invalid-length (dec offset) (byte 127))
    (dotimes [i 4]
      (aset-byte invalid-length (+ offset i) (byte 0)))
    [["invalid public key" invalid-key]
     ["invalid key length" invalid-length]]))
