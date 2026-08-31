(ns linear.handler.vault
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.postgres.vault]
   [linear.usecase.vault :as vault]))

(defn create [context]
  (fn [{:keys [headers]}]
    (try
      (let [idempotency-key (get headers "idempotency-key")]
        (when-not (and (string? idempotency-key)
                       (not-empty idempotency-key))
          (throw (ex-info "Idempotency-Key header is required"
                          {::anomaly/category ::anomaly/incorrect
                           :reason ::missing-idempotency-key})))
        (let [created (vault/create! context
                                     {:data [{:owner nil}]
                                      :idempotency-key idempotency-key})]
          {:status 201
           :body {:id (-> created first key)}}))
      (catch Exception error
        {:status (if (= ::anomaly/incorrect
                        (::anomaly/category (ex-data error)))
                   400
                   500)
         :body {:error (.getMessage error)}}))))
