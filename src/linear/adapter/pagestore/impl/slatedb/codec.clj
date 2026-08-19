(ns linear.adapter.pagestore.impl.slatedb.codec
  (:require
   [boring.core :as cbor]
   [cognitect.anomalies :as anomaly]
   [taoensso.tempel :as tempel])
  (:import
   (java.nio.charset Charset StandardCharsets)
   (java.security MessageDigest)
   (java.util Base64)))

(def ^:private ^Charset utf-8 StandardCharsets/UTF_8)

(defn- string->bytes [^java.lang.String value]
  (.getBytes value utf-8))

(defn- bytes->string [^bytes value]
  (String. value utf-8))

(defn- page-id->key-component [page-id]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (string->bytes page-id)))

(defn head-key []
  (string->bytes "head"))

(defn revision-key [revision-id]
  (string->bytes (str "revision/" revision-id)))

(defn page-key-prefix [page-id]
  (string->bytes (str "page/" (page-id->key-component page-id) "/")))

(defn page-key [page-id revision-id]
  (string->bytes (str "page/"
                      (page-id->key-component page-id)
                      "/"
                      revision-id)))

(defn page-revision-id [page-key-prefix key]
  (subs (bytes->string key) (alength ^bytes page-key-prefix)))

(defn encode-head [head]
  (cbor/encode head))

(defn decode-head [value]
  (some-> value cbor/decode))

(defn- record-key->akm [^bytes record-key]
  (.digest (MessageDigest/getInstance "SHA-256") record-key))

(defn- record-key->akm-options [record-key]
  {:ba-akm (record-key->akm record-key)})

(defn- encrypt [keychain record-key value]
  (tempel/encrypt-with-symmetric-key value keychain (record-key->akm-options record-key)))

(defn- decrypt-and-decode [keychain record-key value]
  (when value
    (try
      (let [decoded (-> (tempel/decrypt-with-symmetric-key value keychain (record-key->akm-options record-key))
                        cbor/decode)]
        (if (nil? decoded)
          (throw (IllegalStateException. "Decoded record is nil"))
          decoded))
      (catch Exception cause
        (throw (ex-info "Encrypted page store record is unreadable"
                        {::anomaly/category ::anomaly/fault
                         :reason             ::unreadable-record}
                        cause))))))

(defn encode-revision [keychain]
  (fn [record-key revision]
    (encrypt keychain record-key (cbor/encode (dissoc revision :pages)))))

(defn decode-revision [keychain]
  (fn [record-key value]
    (decrypt-and-decode keychain record-key value)))

(defn encode-page [keychain]
  (fn [record-key page]
    (encrypt keychain record-key (cbor/encode {:data page}))))

(defn decode-page [keychain]
  (fn [record-key value]
    (some-> (decrypt-and-decode keychain record-key value)
            :data)))
