(ns linear.handler.health-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.health :as health]
   [linear.usecase.healthcheck :as healthcheck]
   [ring.mock.request :refer [request]]))

(defn- checkable [ready? ok?]
  (reify
    healthcheck/Checkable
    (-ready? [_] ready?)
    (-ok? [_] ok?)))

(def ^:private healthy-context
  {:linear.usecase.core/database (checkable true true)
   :linear.usecase.core/evaluator (checkable true true)})

(deftest readiness-handler
  (let [f (health/ready healthy-context)]
    (is (= {:status 200 :body {:status "ready"}}
           (f (request :get "/health/ready")))))
  (let [context (assoc healthy-context
                       :linear.usecase.core/database
                       (checkable false true))]
    (is (= {:status 503 :body {:status "not ready"}}
           ((health/ready context) (request :get "/health/ready"))))))

(deftest liveness-handler
  (is (= {:status 200 :body {:status "ok"}}
         ((health/ok healthy-context) (request :get "/health/ok"))))
  (let [context (assoc healthy-context
                       :linear.usecase.core/evaluator
                       (checkable true false))]
    (is (= {:status 500 :body {:status "not ok"}}
           ((health/ok context) (request :get "/health/ok"))))))
