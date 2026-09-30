(ns linear.handler.vault
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.usecase.vault :as vault]))

(defn create [context]
  (fn [{:keys [headers identity]}]
    (try
      (let [idempotency-key (get headers "idempotency-key")]
        (when-not (and (string? idempotency-key)
                       (not-empty idempotency-key))
          (throw (ex-info "Idempotency-Key header is required"
                          {::anomaly/category ::anomaly/incorrect
                           :reason ::missing-idempotency-key})))
        (let [ids (vault/create! context
                                 {:actor (:sub identity)
                                  :data [{}]
                                  :idempotency-key idempotency-key})]
          {:status 201
           :body {:id (first ids)}}))
      (catch Exception error
        {:status (if (= ::anomaly/incorrect
                        (::anomaly/category (ex-data error)))
                   400
                   500)
         :body {:error (.getMessage error)}}))))

(defn- error-response [error]
  (let [category (::anomaly/category (ex-data error))]
    {:status (case category
               ::anomaly/incorrect 400
               ::anomaly/forbidden 403
               ::anomaly/not-found 404
               ::anomaly/conflict 409
               500)
     :body {:error (if (= ::anomaly/forbidden category)
                     "Forbidden"
                     (.getMessage ^Exception error))}}))

(defn- arguments [request]
  {:actor (get-in request [:identity :sub])
   :vault-id (get-in request [:path-params :id])})

(defn get-state [context]
  (fn [request]
    (try
      (let [{:keys [id state]} (vault/get-state context (arguments request))]
        {:status 200 :body {:id id :state (name state)}})
      (catch Exception error (error-response error)))))

(defn recovery-token [context]
  (fn [request]
    (try
      {:status 200
       :headers {"cache-control" "no-store"}
       :body {:token (vault/recovery-token context (arguments request))}}
      (catch Exception error (error-response error)))))

(defn delete [context]
  (fn [request]
    (try
      (vault/delete! context (arguments request))
      {:status 204}
      (catch Exception error (error-response error)))))

(defn restore [context]
  (fn [{:keys [body-params] :as request}]
    (try
      (when-not (and (map? body-params)
                     (= #{:token} (set (keys body-params)))
                     (string? (:token body-params)))
        (throw (ex-info "Recovery token is required"
                        {::anomaly/category ::anomaly/incorrect})))
      (vault/restore! context (assoc (arguments request) :token (:token body-params)))
      {:status 204}
      (catch Exception error (error-response error)))))
