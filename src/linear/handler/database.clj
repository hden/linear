(ns linear.handler.database
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.spec :as spec]
   [linear.usecase.database :as database]
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]))

(defn- error-response [error]
  (let [category (::anomaly/category (ex-data error))]
    {:status (case category
               ::anomaly/incorrect 400
               ::anomaly/forbidden 403
               ::anomaly/not-found 404
               ::anomaly/conflict 409
               500)
     :body {:error (if (= category ::anomaly/forbidden) "Forbidden" (.getMessage ^Exception error))}}))

(defn- arguments [request]
  {:actor (get-in request [:identity :sub]) :database-id (get-in request [:path-params :id])})

(defn- resource [value]
  (update value :state name))

(defn- require-input [schema value]
  (when-not (spec/valid? schema value)
    (throw (ex-info "Invalid database request" {::anomaly/category ::anomaly/incorrect}))))

(defn create [context]
  (fn [{:keys [body-params headers path-params identity]}]
    (try
      (require-input [:map {:closed true}
                      [:display-name [:string {:min 1}]]
                      [:source {:optional true} [:map {:closed true}
                                                 [:database-id ::model/database-id]
                                                 [:revision-id {:optional true} ::revisions/revision-id]]]]
                     body-params)
      (require-input [:string {:min 1}] (get headers "idempotency-key"))
      (let [id (database/create! context (assoc body-params :actor (:sub identity)
                                           :vault-id (:id path-params)
                                           :idempotency-key (get headers "idempotency-key")))]
        {:status 201 :headers {"location" (str "/control/v1/databases/" id)} :body {:id id}})
      (catch Exception error (error-response error)))))

(defn get-by-id [context]
  (fn [request]
    (try
      {:status 200 :body (resource (database/get-by-id context (arguments request)))}
      (catch Exception error (error-response error)))))

(defn list-by-vault [context]
  (fn [{:keys [query-params path-params identity]}]
    (try
      (let [state (get {"active" :active "closed" :closed "all" :all}
                       (get query-params "state" "active"))]
        (require-input [:enum :active :closed :all] state)
        (let [databases (database/list-by-vault context
                          {:actor (:sub identity) :vault-id (:id path-params) :state state})]
          {:status 200 :body {:databases (mapv resource databases)}}))
      (catch Exception error (error-response error)))))

(defn update-attributes [context]
  (fn [{:keys [body-params] :as request}]
    (try
      (require-input [:map {:closed true} [:display-name [:string {:min 1}]]] body-params)
      (database/update-attributes! context (merge (arguments request) body-params))
      {:status 204}
      (catch Exception error (error-response error)))))

(defn close [context]
  (fn [request]
    (try
      (database/close! context (arguments request))
      {:status 204}
      (catch Exception error (error-response error)))))
