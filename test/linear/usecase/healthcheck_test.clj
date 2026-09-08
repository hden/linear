(ns linear.usecase.healthcheck-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.usecase.core :as core]
   [linear.usecase.healthcheck :as healthcheck]))

(defn- checkable [ready? ok?]
  (reify
    healthcheck/Checkable
    (-ready? [_] ready?)
    (-ok? [_] ok?)))

(deftest ready-test
  (testing "ready"
    (let [context {::core/database  (checkable true false)
                   ::core/evaluator (checkable true false)
                   :logger ::logger}]
      (is (healthcheck/ready? context))))
  (testing "not ready"
    (let [context {::core/database  (checkable true true)
                   ::core/evaluator (checkable false true)}]
      (is (not (healthcheck/ready? context))))))

(deftest ok-test
  (testing "ok"
    (let [context {::core/database  (checkable false true)
                   ::core/evaluator (checkable false true)
                   :logger ::logger}]
      (is (healthcheck/ok? context))))
  (testing "not ok"
    (let [context {::core/database  (checkable true false)
                   ::core/evaluator (checkable true true)}]
      (is (not (healthcheck/ok? context))))))
