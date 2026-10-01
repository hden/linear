(ns linear.handler.http-authorization-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [jsonista.core :as json]
   [linear.adapter.postgres]
   [linear.test :refer [run]]
   [linear.test-data.jwt :as jwt]
   [linear.test-data.postgres :as postgres]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database])
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

(deftest ^:integration database-resources-support-lifecycle-and-closed-source-recovery
  (with-open [fixture (jwt/fixture)]
    (with-system [system (run {:keys [:duct.server.http/jetty :duct.migrator/ragtime]
                               :vars (assoc (jwt/oidc-vars fixture) 'port 0)})]
      (let [client (HttpClient/newHttpClient)
            base (server-url system)
            actor (str "database-manager-" (random-uuid))
            token (jwt/access-token fixture {:subject actor})
            send (fn [method path body key]
                   (http-request client base
                     {:method method :path path :token token
                      :body (when body (json/write-value-as-string body))
                      :headers (cond-> {"content-type" "application/json"}
                                 key (assoc "idempotency-key" key))}))
            vault-id (:id (parse-json (send "POST" "/control/v1/vaults" nil (str (random-uuid)))))
            collection (str "/control/v1/vaults/" vault-id "/databases")
            key (str (random-uuid))
            created (send "POST" collection {:display-name "Primary"} key)]
        (is (= 201 (:status created)))
        (when (= 201 (:status created))
          (let [id (:id (parse-json created))
                path (str "/control/v1/databases/" id)
                ctx {::core/database (:duct.database.sql/hikaricp system)
                     ::core/master-key (:linear.adapter.crypto.tempel/master-key system)
                     ::core/revision-store (:linear.adapter.slatedb.store/store system)
                     ::core/evaluator (:linear.adapter.sqlite.evaluator/evaluator system)}
                root (database/pull ctx {:actor actor :database-id id})]
            (is (pos? (:database-page-count root)))
            (is (= {:id id :vault-id vault-id :display-name "Primary" :state "active"}
                   (parse-json (send "GET" path nil nil))))
            (is (= id (:id (parse-json (send "POST" collection {:display-name "Ignored"} key)))))
            (is (= 204 (:status (send "PATCH" path {:display-name "Renamed"} nil))))
            (is (= (:server-revision root) (:server-revision (database/pull ctx {:actor actor :database-id id}))))
            (database/push! ctx {:actor actor :database-id id
                                 :command {:statements [{:sql "CREATE TABLE example (id INTEGER PRIMARY KEY)" :parameters []}]}})
            (doseq [_ (range 2)] (is (= 204 (:status (send "DELETE" path nil nil)))))
            (is (= {:id id :vault-id vault-id :display-name "Renamed" :state "closed"}
                   (parse-json (send "GET" path nil nil))))
            (is (empty? (:databases (parse-json (send "GET" collection nil nil)))))
            (is (= [id] (mapv :id (:databases (parse-json (send "GET" (str collection "?state=closed") nil nil))))))
            (is (= 409 (:status (send "PATCH" path {:display-name "No"} nil))))
            (is (thrown? clojure.lang.ExceptionInfo (database/pull ctx {:actor actor :database-id id})))
            (is (= id (:id (parse-json (send "POST" collection {:display-name "Ignored"} key)))))
            (doseq [source [{:database-id id} {:database-id id :revision-id (:server-revision root)}]]
              (let [restored (send "POST" collection {:display-name "Recovered" :source source} (str (random-uuid)))
                    restored-id (:id (parse-json restored))]
                (is (= 201 (:status restored)))
                (is (not= id restored-id))
                (is (pos? (:database-page-count (database/pull ctx {:actor actor :database-id restored-id}))))))
            (is (= "closed" (:state (parse-json (send "GET" path nil nil)))))))))))

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

(deftest ^:integration vault-lifecycle-routes-and-deleted-sync-metadata
  (with-open [fixture (jwt/fixture)]
    (with-system [system (run {:keys [:duct.server.http/jetty :duct.migrator/ragtime]
                               :vars (assoc (jwt/oidc-vars fixture) 'port 0)})]
      (let [client (HttpClient/newHttpClient)
            base-url (server-url system)
            owner (str "lifecycle-owner-" (random-uuid))
            token (jwt/access-token fixture {:subject owner})
            {:keys [database-id vault-id]}
            (postgres/create-database! {:datasource (:duct.database.sql/hikaricp system) :actor owner})
            path (str "/control/v1/vaults/" vault-id)
            call (fn [method suffix body]
                   (http-request client base-url {:method method :path (str path suffix)
                                                  :token token :body body
                                                  :headers (when body {"content-type" "application/json"})}))
            recovery (parse-json (call "GET" "/recovery-token" nil))
            metadata-path (str "/d/" database-id "/v2/pipeline")
            metadata-body "{\"requests\":[{\"type\":\"batch\",\"batch\":{\"steps\":[{\"stmt\":{\"sql\":\"SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?\",\"want_rows\":true}}]}}]}"]
        (is (= "active" (:state (parse-json (call "GET" "" nil)))))
        (is (= 204 (:status (call "DELETE" "" nil))))
        (is (= 204 (:status (call "DELETE" "" nil))))
        (is (= "deleted" (:state (parse-json (call "GET" "" nil)))))
        (is (= 200 (:status (call "GET" "/grants" nil))))
        (is (= 409 (:status (call "GET" "/recovery-token" nil))))
        (is (= 409 (:status (http-request client base-url
                              {:method "POST" :path metadata-path :token token
                               :body metadata-body :headers {"content-type" "application/json"}}))))
        (is (= 409 (:status (http-request client base-url
                              {:method "POST" :path (str "/d/" database-id "/pull-updates")
                               :token token :body ""
                               :headers {"content-type" "application/octet-stream"}}))))
        (is (= 409 (:status (http-request client base-url
                              {:method "POST" :path metadata-path :token token
                               :body (json/write-value-as-string
                                       {:requests [{:type "batch"
                                                    :batch {:replication_index nil
                                                            :steps (mapv (fn [sql]
                                                                           {:condition (when-not (= "BEGIN IMMEDIATE" sql)
                                                                                         {:type "not" :cond {:type "is_autocommit"}})
                                                                            :stmt {:sql sql :sql_id nil :args [] :named_args []
                                                                                   :want_rows false :replication_index nil}})
                                                                         ["BEGIN IMMEDIATE" "SELECT 1" "COMMIT"])}}]})
                               :headers {"content-type" "application/json"}}))))
        (is (= 400 (:status (call "PUT" "/recovery-token" "{\"token\":\"invalid\"}"))))
        ;; This fixture's master key is deliberately outside the application context.
        (is (= 400 (:status (call "PUT" "/recovery-token" (json/write-value-as-string recovery)))))
        (let [created (http-request client base-url
                        {:method "POST" :path "/control/v1/vaults" :token token
                         :headers {"idempotency-key" (str (random-uuid))}})
              id (:id (parse-json created))
              created-path (str "/control/v1/vaults/" id)
              args {:token token}
              recovery-response (http-request client base-url
                                  (assoc args :method "GET" :path (str created-path "/recovery-token")))
              recovery-body (:body recovery-response)]
          (is (= 200 (:status recovery-response)))
          (is (= 204 (:status (http-request client base-url (assoc args :method "DELETE" :path created-path)))))
          (dotimes [_ 2]
            (is (= 204 (:status (http-request client base-url
                                  (assoc args :method "PUT" :path (str created-path "/recovery-token")
                                         :body recovery-body :headers {"content-type" "application/json"}))))))
          (is (= "active" (:state (parse-json (http-request client base-url
                                                (assoc args :method "GET" :path created-path)))))))))))
