(ns linear.usecase.keychain
  (:require
   [linear.spec :refer [spec-for]]))

(defprotocol Keychain
  (-encrypt [keychain value options])
  (-decrypt [keychain ciphertext options]))

(defn keychain?
  {:malli/schema [:-> :any :boolean]}
  [value]
  (satisfies? Keychain value))

(defmethod spec-for ::keychain [_]
  [:fn keychain?])

(defprotocol KeyGenerator
  (-generate [generator]))

(defprotocol KeyProtection
  (-id [protection])
  (-wrap [protection keychain options])
  (-unwrap [protection ciphertext options]))

(defmethod spec-for ::key-generator [_]
  [:fn #(satisfies? KeyGenerator %)])

(defmethod spec-for ::key-protection [_]
  [:fn #(satisfies? KeyProtection %)])

(defn generate
  {:malli/schema [:-> ::key-generator ::keychain]}
  [generator]
  (-generate generator))

(defn id
  {:malli/schema [:-> ::key-protection :string]}
  [protection]
  (-id protection))

(defn wrap
  {:malli/schema [:->
                  ::key-protection
                  [:map
                   [:keychain ::keychain]
                   [:associated-data {:optional true} [:fn bytes?]]]
                  [:fn bytes?]]}
  [protection {:keys [keychain] :as options}]
  (-wrap protection keychain (dissoc options :keychain)))

(defn unwrap
  "Returns nil for invalid input or authentication failure. Key service and
  integrity failures throw a fault with ::key-service-failed; unexpected errors
  propagate."
  {:malli/schema [:->
                  ::key-protection
                  [:map
                   [:ciphertext [:fn bytes?]]
                   [:associated-data {:optional true} [:fn bytes?]]]
                  [:maybe ::keychain]]}
  [protection {:keys [ciphertext] :as options}]
  (-unwrap protection ciphertext (dissoc options :ciphertext)))

(defn encrypt
  {:malli/schema [:->
                  ::keychain
                  [:map
                   [:value [:fn bytes?]]
                   [:associated-data {:optional true} [:fn bytes?]]]
                  [:fn bytes?]]}
  [keychain {:keys [value] :as options}]
  (-encrypt keychain value (dissoc options :value)))

(defn decrypt
  "Returns nil for invalid input or authentication failure; unexpected errors propagate."
  {:malli/schema [:->
                  ::keychain
                  [:map
                   [:ciphertext [:fn bytes?]]
                   [:associated-data {:optional true} [:fn bytes?]]]
                  [:maybe [:fn bytes?]]]}
  [keychain {:keys [ciphertext] :as options}]
  (-decrypt keychain ciphertext (dissoc options :ciphertext)))
