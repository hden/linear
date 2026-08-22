(ns linear.handler.health-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.health :as health]
   [linear.protocol :as protocol]
   [ring.mock.request :refer [request]]))

(def ^:private ctrl (reify protocol/Controllable
                      (-ready? [_] true)))

(deftest readiness-handler
  (let [f (health/ready {:linear.usecase.core/postgres-datasource ctrl})]
    (is (= {:status 200 :body {:status "ready"}}
           (f (request :get "/health/ready"))))))
