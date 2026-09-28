(ns linear.middleware.authentication
  (:require
   [buddy.sign.jwt :as jwt]
   [clojure.string :as str]
   [integrant.core :as ig]))

(def ^:private public-paths
  #{"/health/ok" "/health/ready"})

(defn- bearer-token [request]
  (some->> (get-in request [:headers "authorization"])
           (re-matches #"(?i)^Bearer ([^\s]+)$")
           second))

(defn- verified-identity [request {:keys [provider issuer audience]}]
  (when-let [token (bearer-token request)]
    (try
      (let [{:keys [sub exp]}
            (jwt/unsign token provider {:alg :rs256
                                        :iss issuer
                                        :aud audience})]
        (when (and (string? sub)
                   (not (str/blank? sub))
                   (integer? exp))
          {:sub sub}))
      (catch clojure.lang.ExceptionInfo _
        nil)
      (catch IllegalArgumentException _
        nil)
      (catch ClassCastException _
        nil))))

(defn- unauthorized-response []
  {:status 401
   :headers {"content-type" "application/json"
             "www-authenticate" "Bearer"}
   :body "{\"error\":\"Unauthorized\"}"})

(defn wrap-authentication
  [handler {:keys [provider issuer audience] :as options}]
  (when-not (and (ifn? provider)
                 (string? issuer)
                 (not (str/blank? issuer))
                 (string? audience)
                 (not (str/blank? audience)))
    (throw (ex-info "OIDC issuer, audience, and JWKS provider are required"
                    {:reason ::invalid-configuration})))
  (fn [request]
    (if (contains? public-paths (:uri request))
      (handler request)
      (if-let [identity (verified-identity request options)]
        (handler (assoc request :identity identity))
        (unauthorized-response)))))

(defmethod ig/init-key ::middleware [_ options]
  #(wrap-authentication % options))
