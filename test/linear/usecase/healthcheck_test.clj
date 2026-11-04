(ns linear.usecase.healthcheck-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.usecase.core :as core]
   [linear.usecase.healthcheck :as healthcheck]))

(defn- checkable [ready? {:keys [ok?]}]
  (reify
    healthcheck/Checkable
    (-ready? [_] ready?)
    (-ok? [_] ok?)))

(deftest ready-test
  (testing "ready"
    (let [context {::core/database  (checkable true {:ok? false})
                   ::core/evaluator (checkable true {:ok? false})
                   :logger ::logger}]
      (is (healthcheck/ready? context))))
  (testing "not ready"
    (let [context {::core/database  (checkable true {:ok? true})
                   ::core/evaluator (checkable false {:ok? true})}]
      (is (not (healthcheck/ready? context))))))

(deftest ok-test
  (testing "ok"
    (let [context {::core/database  (checkable false {:ok? true})
                   ::core/evaluator (checkable false {:ok? true})
                   :logger ::logger}]
      (is (healthcheck/ok? context))))
  (testing "not ok"
    (let [context {::core/database  (checkable true {:ok? false})
                   ::core/evaluator (checkable true {:ok? true})}]
      (is (not (healthcheck/ok? context))))))

(deftest dependency-exceptions-make-healthchecks-false
  (let [failing (reify healthcheck/Checkable
                  (-ready? [_]
                    (throw (IllegalStateException. "dependency failed")))
                  (-ok? [_]
                    (throw (IllegalStateException. "dependency failed"))))
        context {::core/database failing}]
    (is (false? (healthcheck/ready? context)))
    (is (false? (healthcheck/ok? context)))))
