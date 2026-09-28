(ns linear.test-data.jwt
  (:require
   [buddy.sign.jwt :as jwt])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.io Closeable)
   (java.math BigInteger)
   (java.net InetSocketAddress)
   (java.nio.charset StandardCharsets)
   (java.security KeyPair KeyPairGenerator)
   (java.security.interfaces RSAPublicKey)
   (java.time Instant)
   (java.util Base64)))

(def ^:private key-id "linear-test-key")
(def ^:private issuer "https://issuer.linear.test/")
(def ^:private audience "https://api.linear.test")

(defn- base64url [bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- unsigned-bytes [^BigInteger value]
  (let [bytes (.toByteArray value)]
    (if (and (> (alength bytes) 1)
             (zero? (aget bytes 0)))
      (java.util.Arrays/copyOfRange bytes 1 (alength bytes))
      bytes)))

(defn- jwks-json [^RSAPublicKey public-key]
  (format (str "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"%s\","
               "\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"%s\",\"e\":\"%s\"}]}")
          key-id
          (base64url (unsigned-bytes (.getModulus public-key)))
          (base64url (unsigned-bytes (.getPublicExponent public-key)))))

(defn- respond! [^HttpExchange exchange body]
  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange 200 (alength bytes))
    (with-open [output (.getResponseBody exchange)]
      (.write output bytes))))

(defrecord Fixture [^HttpServer server ^KeyPair key-pair issuer audience jwks-url]
  Closeable
  (close [_]
    (.stop server 0)))

(defn fixture []
  (let [generator (doto (KeyPairGenerator/getInstance "RSA")
                    (.initialize 2048))
        key-pair (.generateKeyPair generator)
        jwks     (jwks-json (.getPublic key-pair))
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/.well-known/jwks.json"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (respond! exchange jwks))))
    (.start server)
    (->Fixture server
               key-pair
               issuer
               audience
               (str "http://127.0.0.1:"
                    (.getPort (.getAddress server))
                    "/.well-known/jwks.json"))))

(defn oidc-vars [{:keys [issuer audience jwks-url]}]
  {'oidc-issuer issuer
   'oidc-audience audience
   'oidc-jwks-url jwks-url})

(defn access-token
  [{:keys [^KeyPair key-pair issuer audience]}
   {:keys [subject claims without]
    :or   {claims {}
           without #{}}}]
  (let [now (.getEpochSecond (Instant/now))]
    (jwt/sign (apply dissoc
                     (merge {:iss issuer
                             :aud audience
                             :sub subject
                             :exp (+ now 300)
                             :nbf (- now 1)}
                            claims)
                     without)
              (.getPrivate key-pair)
              {:alg :rs256
               :header {:kid key-id}})))
