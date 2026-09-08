(ns linear.usecase.keychain
  (:require
   [linear.spec :refer [spec-for]]))

(defprotocol Keychain
  (-id [keychain])
  (-encrypt [keychain x options])
  (-decrypt [keychain ciphertext options]))

(defn keychain?
  {:malli/schema [:-> :any :boolean]}
  [value]
  (satisfies? Keychain value))

(defmethod spec-for ::keychain [_]
  [:fn keychain?])

(defn id
  {:malli/schema [:-> ::keychain :any]}
  [keychain]
  (-id keychain))

(defn encrypt
  {:malli/schema [:function
                  [:-> ::keychain :any [:fn bytes?]]
                  [:->
                   ::keychain
                   :any
                   [:map [:associated-data {:optional true} [:fn bytes?]]]
                   [:fn bytes?]]]}
  ([keychain x]
   (-encrypt keychain x {}))
  ([keychain x options]
   (-encrypt keychain x options)))

(defn decrypt
  {:malli/schema [:function
                  [:-> ::keychain [:fn bytes?] :any]
                  [:->
                   ::keychain
                   [:fn bytes?]
                   [:map [:associated-data {:optional true} [:fn bytes?]]]
                   :any]]}
  ([keychain ciphertext]
   (-decrypt keychain ciphertext {}))
  ([keychain ciphertext options]
   (-decrypt keychain ciphertext options)))
