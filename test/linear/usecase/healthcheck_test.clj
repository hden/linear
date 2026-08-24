(ns linear.usecase.healthcheck-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.protocol :as protocol]
   [linear.usecase.core :as core]
   [linear.usecase.healthcheck :as healthcheck]))

(defn- checkable-evaluator [ready? ok?]
  (reify
    protocol/Checkable
    (-ready? [_] ready?)
    (-ok? [_] ok?)
    protocol/Evaluator
    (-eval [_ _] {})
    (-vacuum [_ _] {})))

(deftest ready-test
  (testing "ready"
    (let [context {::core/postgres-datasource (checkable-evaluator true false)
                   ::core/sqlite-evaluator      (checkable-evaluator true false)
                   :logger                    ::logger}]
      (is (healthcheck/ready? context))))
  (testing "not ready"
    (let [context {::core/postgres-datasource (checkable-evaluator true true)
                   ::core/sqlite-evaluator      (checkable-evaluator false true)}]
      (is (not (healthcheck/ready? context))))))

(deftest ok-test
  (testing "ok"
    (let [context {::core/postgres-datasource (checkable-evaluator false true)
                   ::core/sqlite-evaluator      (checkable-evaluator false true)
                   :logger                    ::logger}]
      (is (healthcheck/ok? context))))
  (testing "not ok"
    (let [context {::core/postgres-datasource (checkable-evaluator true false)
                   ::core/sqlite-evaluator      (checkable-evaluator true true)}]
      (is (not (healthcheck/ok? context))))))
