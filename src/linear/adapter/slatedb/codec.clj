(ns linear.adapter.slatedb.codec
  (:require
   [boring.core :as cbor]
   [cognitect.anomalies :as anomaly]
   [taoensso.tempel :as tempel])
  (:import
   (java.security MessageDigest)))

(def ^:private keychain-schema
  [:fn tempel/keychain?])

(def ^:private bytes-schema
  [:fn bytes?])

(defn encode-head
  {:malli/schema [:-> :map bytes-schema]}
  [head]
  (cbor/encode head))

(defn decode-head
  {:malli/schema [:-> [:maybe bytes-schema] [:maybe :map]]}
  [value]
  (some-> value cbor/decode))

(defn- record-key->akm [record-key]
  (.digest (MessageDigest/getInstance "SHA-256") ^bytes record-key))

(defn- akm-options [record-key]
  {:ba-akm (record-key->akm record-key)})

(defn- encrypt [keychain record-key value]
  (tempel/encrypt-with-symmetric-key value keychain (akm-options record-key)))

(defn- decrypt-and-decode [keychain record-key value]
  (when value
    (try
      (let [decoded (-> (tempel/decrypt-with-symmetric-key
                          value keychain (akm-options record-key))
                        cbor/decode)]
        (if (nil? decoded)
          (throw (IllegalStateException. "Decoded record is nil"))
          decoded))
      (catch Exception cause
        (throw (ex-info "Encrypted SlateDB record is unreadable"
                        {::anomaly/category ::anomaly/fault
                         :reason ::unreadable-record}
                        cause))))))

(defn encode-revision
  {:malli/schema [:-> keychain-schema bytes-schema :map bytes-schema]}
  [keychain record-key revision]
  (encrypt keychain record-key (cbor/encode revision)))

(defn decode-revision
  {:malli/schema [:-> keychain-schema bytes-schema [:maybe bytes-schema] [:maybe :map]]}
  [keychain record-key value]
  (decrypt-and-decode keychain record-key value))

(defn encode-page
  {:malli/schema [:-> keychain-schema bytes-schema bytes-schema bytes-schema]}
  [keychain record-key page]
  (encrypt keychain record-key (cbor/encode {:data page})))

(defn decode-page
  {:malli/schema [:-> keychain-schema bytes-schema [:maybe bytes-schema] [:maybe bytes-schema]]}
  [keychain record-key value]
  (some-> (decrypt-and-decode keychain record-key value) :data))
