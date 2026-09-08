(ns linear.adapter.crypto.tempel
  (:require
   [integrant.core :as ig]
   [labrador.core :as lab]
   [linear.usecase.keychain :as keychain]
   [taoensso.tempel :as tempel])
  (:import
   (java.security MessageDigest)))

(defn- associated-data->akm [associated-data]
  (.digest (MessageDigest/getInstance "SHA-256") ^bytes associated-data))

(defn- encryption-options [{:keys [associated-data]}]
  (cond-> {}
    associated-data (assoc :ba-akm (associated-data->akm associated-data))))

(extend-type taoensso.tempel.keys.KeyChain
  keychain/Keychain
  (-id [keychain]
    (::id (meta keychain)))
  (-encrypt [keychain x options]
    (if (keychain/keychain? x)
      (tempel/encrypt-keychain x (merge {:key-sym keychain} (encryption-options options)))
      (tempel/encrypt-with-symmetric-key x keychain (encryption-options options))))
  (-decrypt [keychain ciphertext options]
    (if (= :encrypted-keychain (:kind (tempel/public-data ciphertext)))
      (some-> (tempel/keychain-decrypt ciphertext (merge {:key-sym keychain} (encryption-options options)))
              (vary-meta assoc ::id nil))
      (tempel/decrypt-with-symmetric-key ciphertext keychain (encryption-options options)))))

(defn keychain
  ([value]
   (keychain nil value))
  ([id value]
   (vary-meta value assoc ::id id)))

(defn new-keychain []
  (keychain (tempel/keychain)))

(lab/defretriever master-key-retriever
  {:tag :linear.usecase.keychain/master-key}
  [{configured :linear.usecase.core/master-key} ids]
  (when (and configured (contains? ids (keychain/id configured)))
    {(keychain/id configured) configured}))

(defmethod ig/init-key ::keychain [_ _]
  new-keychain)

(defmethod ig/init-key ::master-key [_ {:keys [id]}]
  (keychain id (tempel/keychain)))
