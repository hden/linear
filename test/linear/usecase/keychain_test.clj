(ns linear.usecase.keychain-test
  (:require
   [clojure.test :refer [deftest is]]
   [labrador.core :as lab]
   [linear.adapter.crypto.tempel]
   [linear.usecase.core :as core]
   [linear.usecase.keychain :as keychain]
   [urania.core :as u]))

(deftest fetches-only-the-configured-master-key
  (let [decryptions (atom 0)
        configured
        (reify keychain/Keychain
          (-id [_] "configured")
          (-encrypt [_ _ _] nil)
          (-decrypt [_ _ _]
            (swap! decryptions inc)))
        fetch (fn [id]
                (u/run!! (lab/fetch ::keychain/master-key id)
                         {:env {::core/master-key configured}}))]
    (is (identical? configured (fetch "configured")))
    (is (nil? (fetch "other")))
    (is (zero? @decryptions))))
