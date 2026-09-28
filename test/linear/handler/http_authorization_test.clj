(ns linear.handler.http-authorization-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [jsonista.core :as json]
   [linear.adapter.postgres]
   [linear.test :refer [run]]
   [linear.test-data.jwt :as jwt]
   [linear.test-data.postgres :as postgres])
  (:import
   (java.net URI URLEncoder)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.nio.charset StandardCharsets)
   (org.eclipse.jetty.server Server)))

(def ^:private json-mapper json/keyword-keys-object-mapper)

(defn- server-url [system]
  (let [^Server server (get-in system [:duct.server.http/jetty :server])
        port (.getLocalPort (first (.getConnectors server)))]
    (str "http://127.0.0.1:" port)))

(defn- http-request
  [client base-url {:keys [body headers method path token]}]
  (let [builder (HttpRequest/newBuilder (URI/create (str base-url path)))
        builder (reduce-kv (fn [request name value]
                             (.header request name value))
                           builder
                           (cond-> headers
                             token (assoc "authorization" (str "Bearer " token))))
        publisher (if body
                    (HttpRequest$BodyPublishers/ofString body)
                    (HttpRequest$BodyPublishers/noBody))
        request (.build (.method builder method publisher))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn- parse-json [response]
  (json/read-value (:body response) json-mapper))

(defn- encode-path-segment [value]
  (URLEncoder/encode value StandardCharsets/UTF_8))

(deftest ^:integration authenticated-http-routes-enforce-vault-grants
  (with-open [fixture (jwt/fixture)]
    (with-system [system (run {:keys [:duct.server.http/jetty
                                      :duct.migrator/ragtime]
                               :vars (assoc (jwt/oidc-vars fixture) 'port 0)})]
      (let [client     (HttpClient/newHttpClient)
            base-url  (server-url system)
            owner     (str "auth0|owner-" (random-uuid))
            reader    "auth0|reader/email@example.com"
            owner-jwt (jwt/access-token fixture {:subject owner})
            reader-jwt (jwt/access-token fixture {:subject reader})
            datasource (:duct.database.sql/hikaricp system)
            {:keys [database-id vault-id]}
            (postgres/create-database! {:datasource datasource :actor owner})
            metadata-body
            "{\"requests\":[{\"type\":\"batch\",\"batch\":{\"steps\":[{\"stmt\":{\"sql\":\"SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?\",\"want_rows\":true}}]}}]}"
            metadata-path (str "/d/" database-id "/v2/pipeline")]
        (is (= 200 (:status (http-request client base-url
                              {:method "GET" :path "/health/ok"}))))
        (is (= 401 (:status (http-request client base-url
                              {:method "POST"
                               :path "/control/v1/vaults"
                               :headers {"idempotency-key" "missing-token"}}))))
        (let [idempotency-key (str "http-vault-" (random-uuid))
              created (http-request client base-url
                                    {:method "POST"
                                     :path "/control/v1/vaults"
                                     :token owner-jwt
                                     :headers {"idempotency-key" idempotency-key}})
              created-id (:id (parse-json created))
              grants-path (str "/control/v1/vaults/" created-id "/grants")]
          (is (= 201 (:status created)))
          (is (= [{:subject owner :permission "manage"}]
                 (:grants
                   (parse-json
                     (http-request client base-url
                                   {:method "GET"
                                    :path grants-path
                                    :token owner-jwt})))))
          (is (= 204 (:status (http-request client base-url
                                {:method "DELETE"
                                 :path (str grants-path "/" (encode-path-segment owner))
                                 :token owner-jwt}))))
          (let [replayed (http-request client base-url
                                       {:method "POST"
                                        :path "/control/v1/vaults"
                                        :token owner-jwt
                                        :headers {"idempotency-key" idempotency-key}})]
            (is (= 201 (:status replayed)))
            (is (= created-id (:id (parse-json replayed)))))
          (is (= 403 (:status (http-request client base-url
                                {:method "GET"
                                 :path grants-path
                                 :token owner-jwt})))))
        (is (= 401 (:status (http-request client base-url
                              {:method "POST"
                               :path metadata-path
                               :body "{"
                               :headers {"content-type" "application/json"}}))))
        (is (= 403 (:status (http-request client base-url
                              {:method "POST"
                               :path metadata-path
                               :body metadata-body
                               :token reader-jwt
                               :headers {"content-type" "application/json"}}))))
        (let [grant-path (str "/control/v1/vaults/" vault-id
                              "/grants/" (encode-path-segment reader))
              response (http-request client base-url
                                     {:method "PUT"
                                      :path grant-path
                                      :body "{\"permission\":\"pull\"}"
                                      :token owner-jwt
                                      :headers {"content-type" "application/json"}})]
          (is (= 204 (:status response)))
          (is (= 200 (:status (http-request client base-url
                                {:method "POST"
                                 :path metadata-path
                                 :body metadata-body
                                 :token reader-jwt
                                 :headers {"content-type" "application/json"}}))))
          (is (= 403 (:status (http-request client base-url
                                {:method "GET"
                                 :path (str "/control/v1/vaults/" vault-id "/grants")
                                 :token reader-jwt}))))
          (is (= [{:subject owner :permission "manage"}
                  {:subject reader :permission "pull"}]
                 (:grants
                   (parse-json
                     (http-request client base-url
                                   {:method "GET"
                                    :path (str "/control/v1/vaults/" vault-id "/grants")
                                    :token owner-jwt})))))
          (is (= 204 (:status (http-request client base-url
                                {:method "DELETE"
                                 :path grant-path
                                 :token owner-jwt}))))
          (is (= 403 (:status (http-request client base-url
                                {:method "POST"
                                 :path metadata-path
                                 :body metadata-body
                                 :token reader-jwt
                                 :headers {"content-type" "application/json"}})))))))))
