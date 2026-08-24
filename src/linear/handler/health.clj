(ns linear.handler.health
  (:require
   [linear.usecase.healthcheck :refer [ok? ready?]]))

(defn ready [context]
  (fn [_]
    (if (ready? context)
      {:status 200
       :body {:status "ready"}}
      {:status 503
       :body {:status "not ready"}})))

(defn ok [context]
  (fn [_]
    (if (ok? context)
      {:status 200
       :body {:status "ok"}}
      {:status 500
       :body {:status "not ok"}})))
