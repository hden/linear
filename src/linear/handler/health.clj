(ns linear.handler.health
  (:require
   [linear.usecase.healthcheck :refer [ready?]]))

(defn ready [context]
  (fn [_]
    (if (ready? context)
      {:status 200
       :body {:status "ready"}}
      {:status 500
       :body {:status "not ready"}})))
