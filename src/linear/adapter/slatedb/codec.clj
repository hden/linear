(ns linear.adapter.slatedb.codec
  (:require
   [boring.core :as cbor]
   [cognitect.anomalies :as anomaly]
   [linear.usecase.keychain :as keychain]))

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

(defn- encrypt [keychain record-key value]
  (keychain/encrypt keychain {:associated-data record-key :value value}))

(defn- decrypt-and-decode [keychain record-key value]
  (when value
    (try
      (let [decoded (-> (keychain/decrypt keychain {:associated-data record-key :ciphertext value})
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
  {:malli/schema [:->
                  [:fn keychain/keychain?]
                  [:map [:record-key bytes-schema]
                   [:revision :map]]
                  bytes-schema]}
  [keychain {:keys [record-key revision]}]
  (encrypt keychain record-key (cbor/encode revision)))

(defn decode-revision
  {:malli/schema [:->
                  [:fn keychain/keychain?]
                  [:map [:record-key bytes-schema]
                   [:value [:maybe bytes-schema]]]
                  [:maybe :map]]}
  [keychain {:keys [record-key value]}]
  (decrypt-and-decode keychain record-key value))

(defn encode-page
  {:malli/schema [:->
                  [:fn keychain/keychain?]
                  [:map [:record-key bytes-schema]
                   [:page bytes-schema]]
                  bytes-schema]}
  [keychain {:keys [record-key page]}]
  (encrypt keychain record-key (cbor/encode {:data page})))

(defn decode-page
  {:malli/schema [:->
                  [:fn keychain/keychain?]
                  [:map [:record-key bytes-schema]
                   [:value [:maybe bytes-schema]]]
                  [:maybe bytes-schema]]}
  [keychain {:keys [record-key value]}]
  (some-> (decrypt-and-decode keychain record-key value) :data))
