(ns linear.handler.core
  (:require
   [cognitect.anomalies :as anomaly]))

(defn anomaly [error]
  (let [data (reduce (fn [result cause]
                       (merge (select-keys (ex-data cause)
                                [::anomaly/category ::anomaly/message :reason :statement-index])
                              result))
                     {}
                     (take-while some? (iterate ex-cause error)))]
    (merge {::anomaly/category ::anomaly/fault
            ::anomaly/message (.getMessage ^Exception error)}
           data)))

(defn http-status [{::anomaly/keys [category]}]
  (case category
    ::anomaly/incorrect 400
    ::anomaly/forbidden 403
    ::anomaly/not-found 404
    ::anomaly/conflict 409
    500))

(defn error-response [{::anomaly/keys [category message] :as arg-map}]
  {:status (http-status arg-map)
   :body {:error (if (= ::anomaly/forbidden category) "Forbidden" message)}})
