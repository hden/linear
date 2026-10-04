(ns linear.test-data.vault
  "In-memory capabilities for vault behavior and HTTP boundary tests."
  (:require
   [linear.usecase.core :as core]
   [linear.usecase.grant :as grant]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]))

(defn fixture
  [{:keys [stored permission unwrap]
    :or {stored {:id "v-test" :encrypted-by "master" :ciphertext (byte-array [1 2 3])}
         permission :manage}}]
  (let [state (atom stored)
        calls (atom [])
        data-key (reify keychain/Keychain
                   (-encrypt [_ _ _] (throw (AssertionError. "Data encryption is not used by vault lifecycle operations")))
                   (-decrypt [_ _ _] (throw (AssertionError. "Data decryption is not used by vault lifecycle operations"))))
        master (reify
                 keychain/KeyGenerator
                 (-generate [_] data-key)
                 keychain/KeyProtection
                 (-id [_] "master")
                 (-wrap [_ value options]
                   (swap! calls conj [:wrap (assoc options :keychain value)])
                   (byte-array [1 2 3]))
                 (-unwrap [_ ciphertext options]
                   (swap! calls conj [:unwrap (assoc options :ciphertext ciphertext)])
                   (if unwrap (unwrap) data-key)))
        database (reify
                   vault/Database
                   (-create! [_ {:keys [data] :as arg-map}]
                     (swap! calls conj [:create arg-map])
                     (reset! state (first data))
                     {:ids (mapv :id data) :created? true})
                   (-read [_ arg-map]
                     (swap! calls conj [:read arg-map])
                     @state)
                   (-store-key! [_ arg-map]
                     (swap! calls conj [:write arg-map])
                     (swap! state merge (select-keys arg-map [:ciphertext :encrypted-by]))
                     nil)
                   grant/Store
                   (-permission [_ arg-map]
                     (swap! calls conj [:permission arg-map])
                     permission)
                   (-list-grants [_ _] (throw (AssertionError. "Grant listing is not used by vault lifecycle operations")))
                   (-set-grants! [_ arg-map]
                     (swap! calls conj [:grants arg-map])
                     true)
                   (-revoke-grants! [_ _] (throw (AssertionError. "Grant revocation is not used by vault lifecycle operations"))))]
    {:state state
     :calls calls
     :data-key data-key
     :context {::core/key-service master
               ::core/database (reify transaction/Transactable
                                 (-transact [_ f _] (f database)))}}))

(defn calls-for [calls {:keys [operation]}]
  (into [] (keep (fn [[called arg-map]] (when (= operation called) arg-map))) @calls))
