(ns linear.adapter.crypto.tempel
  (:require
   [linear.adapter.crypto.core :as crypto]
   [linear.usecase.keychain :as keychain])
  (:import
   (java.io Closeable)))

(defrecord ^:private KeyService [key-id master]
  keychain/KeyGenerator
  (-generate [_] (crypto/new-keychain))
  keychain/KeyProtection
  (-id [_] key-id)
  (-wrap [_ data-key options]
    (crypto/wrap master (assoc options :keychain data-key)))
  (-unwrap [_ ciphertext options]
    (crypto/unwrap master (assoc options :ciphertext ciphertext)))
  Closeable
  (close [_]))

(alter-meta! #'->KeyService assoc :private true)
(alter-meta! #'map->KeyService assoc :private true)

(defn open
  {:malli/schema [:-> [:map [:key-id :string]]
                  [:and ::keychain/key-generator ::keychain/key-protection]]}
  [{:keys [key-id]}]
  (->KeyService key-id (crypto/new-keychain)))
