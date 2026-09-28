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
