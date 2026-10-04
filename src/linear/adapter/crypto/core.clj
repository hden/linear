(ns linear.adapter.crypto.core
  (:require
   [linear.usecase.keychain :as keychain]
   [taoensso.tempel :as tempel]
   [taoensso.tempel.keys :as keys])
  (:import
   (java.io EOFException)
   (java.security MessageDigest)
   (java.security.spec InvalidKeySpecException)))

(defn- associated-data->akm [associated-data]
  (.digest (MessageDigest/getInstance "SHA-256") ^bytes associated-data))

(defn- encryption-options [{:keys [associated-data]}]
  (cond-> {}
    associated-data (assoc :ba-akm (associated-data->akm associated-data))))

(defn- invalid-input? [error]
  (let [{:keys [ns read identifier value context num-keys-tried key-algo key-type key-id]
         failure :error} (ex-data error)]
    (or (and (= ns "taoensso.tempel.df")
             (or (= (:expected read) [84 80 76])
                 (some? identifier)
                 (= (:expected value) 0)))
        (and (= ns "taoensso.tempel.impl")
             (= failure :decode-failure)
             (instance? InvalidKeySpecException (ex-cause error)))
        (and (= ns "taoensso.tempel.keys")
             (or (and (= context `tempel/decrypt-with-symmetric-key)
                      (some? num-keys-tried))
                 (and (= key-algo :sym)
                      (= key-type :sym)
                      (string? key-id)))))))

(defn- decrypt-input [f]
  (try
    (f)
    (catch EOFException _ nil)
    (catch NegativeArraySizeException _ nil)
    (catch clojure.lang.ExceptionInfo error
      (if (invalid-input? error) nil (throw error)))))

(extend-type taoensso.tempel.keys.KeyChain
  keychain/Keychain
  (-encrypt [keychain value options]
    (tempel/encrypt-with-symmetric-key value keychain (encryption-options options)))
  (-decrypt [keychain ciphertext options]
    (decrypt-input
      #(tempel/decrypt-with-symmetric-key ciphertext keychain (encryption-options options)))))

(defn new-keychain []
  (tempel/keychain))

(defn wrap
  [master {:keys [keychain] :as options}]
  (tempel/encrypt-keychain keychain (merge {:key-sym master} (encryption-options options))))

(defn unwrap
  [master {:keys [ciphertext] :as options}]
  (decrypt-input
    #(when (= :encrypted-keychain (:kind (tempel/public-data ciphertext)))
       (tempel/keychain-decrypt ciphertext (merge {:key-sym master} (encryption-options options))))))

(defn freeze
  {:malli/schema [:-> [:fn tempel/keychain?] [:fn bytes?]]}
  [keychain]
  (force (:ba-kc_ (keys/keychain-freeze keychain))))

(defn thaw
  {:malli/schema [:-> [:fn bytes?] ::keychain/keychain]}
  [value]
  (keys/keychain-thaw value))
