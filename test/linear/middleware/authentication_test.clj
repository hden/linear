(ns linear.middleware.authentication-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [duct.auth.jwks]
   [duct.main.config :as duct-config]
   [duct.test :refer [with-system]]
   [integrant.core :as ig]
   [linear.middleware.authentication :as authentication]
   [linear.test-data.jwt :as jwt]))

(defn- application-config []
  (letfn [(read-config [file]
            (ig/read-string {:readers {'duct/include #(read-config (io/file %))
                                       'duct/resource io/resource}}
                            (slurp file)))]
    (read-config (io/file "duct.edn"))))

(deftest oidc-jwks-url-is-optional-at-application-startup
  (let [config (-> (application-config)
                   (assoc-in [:vars 'oidc-issuer :default]
                             "https://issuer.linear.test/")
                   (assoc-in [:vars 'oidc-audience :default]
                             "https://api.linear.test"))]
    (with-system [system (duct-config/init config
                           {:profiles [:test]
                            :keys [:duct.auth.jwks/provider]})]
      (is (fn? (:duct.auth.jwks/provider system))))))

(deftest authentication-protects-application-routes-and-leaves-health-public
  (with-open [fixture (jwt/fixture)]
    (let [{:keys [issuer audience jwks-url]} fixture
          provider (ig/init-key :duct.auth.jwks/provider
                                {:domain issuer :url jwks-url})
          middleware (ig/init-key ::authentication/middleware
                                  {:provider provider
                                   :issuer issuer
                                   :audience audience})
          handler (middleware (fn [request]
                                {:status 200
                                 :body (:identity request)}))
          token (jwt/access-token fixture {:subject "auth0|actor/one"})]
      (is (= {:status 200 :body nil}
             (handler {:uri "/health/ready"})))
      (is (= 401 (:status (handler {:uri "/control/v1/vaults"}))))
      (is (= 401 (:status (handler {:uri "/control/v1/vaults"
                                    :headers {"authorization" "Bearer invalid"}}))))
      (doseq [invalid-token [(jwt/access-token fixture {:subject "actor" :without #{:exp}})
                             (jwt/access-token fixture {:subject ""})
                             (jwt/access-token fixture {:subject "actor"
                                                        :claims {:exp "soon"}})]]
        (is (= 401 (:status (handler {:uri "/control/v1/vaults"
                                      :headers {"authorization"
                                                (str "Bearer " invalid-token)}})))))
      (is (= {:status 200
              :body {:sub "auth0|actor/one"}}
             (handler {:uri "/control/v1/vaults"
                       :headers {"authorization" (str "Bearer " token)}})))
      (let [downstream-error (ex-info "downstream" {:reason ::downstream})
            throwing-handler
            ((ig/init-key ::authentication/middleware
                          {:provider provider :issuer issuer :audience audience})
             (fn [_] (throw downstream-error)))
            thrown (try
                     (throwing-handler {:uri "/control/v1/vaults"
                                        :headers {"authorization" (str "Bearer " token)}})
                     (catch clojure.lang.ExceptionInfo error error))]
        (is (identical? downstream-error thrown))))))
