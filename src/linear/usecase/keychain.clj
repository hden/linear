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
  {:malli/schema [:->
                  ::keychain
                  [:map
                   [:value :any]
                   [:associated-data {:optional true} [:fn bytes?]]]
                  [:fn bytes?]]}
  [keychain {:keys [value] :as options}]
  (-encrypt keychain value (dissoc options :value)))

(defn decrypt
  {:malli/schema [:->
                  ::keychain
                  [:map
                   [:ciphertext [:fn bytes?]]
                   [:associated-data {:optional true} [:fn bytes?]]]
                  :any]}
  [keychain {:keys [ciphertext] :as options}]
  (-decrypt keychain ciphertext (dissoc options :ciphertext)))
