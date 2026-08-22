(ns linear.usecase.healthcheck-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.protocol :as protocol]
   [linear.usecase.core :as core]
   [linear.usecase.healthcheck :as healthcheck]))

(def ^:private ctrl (reify protocol/Controllable
                      (-ready? [_] true)))

(deftest readiness
  (testing "ok"
    (let [context {::core/postgres-datasource ctrl}]
      (is (healthcheck/ready? context)))))
