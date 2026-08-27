(ns linear.usecase.healthcheck-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.protocol :as protocol]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database]
   [linear.usecase.healthcheck :as healthcheck]))

(defn- checkable-evaluator [ready? ok?]
  (reify
    protocol/Checkable
    (-ready? [_] ready?)
    (-ok? [_] ok?)
    database/Evaluator
    (-evaluate [_ _] {})))

(defn- database-store []
  (reify
    database/SnapshotReader
    (-latest-snapshot [_ _] nil)
    database/RevisionWriter
    (-publish-next-revision! [_ _ revision] revision)))

(deftest exposes-the-configured-database-capabilities
  (let [store     (database-store)
        evaluator (checkable-evaluator true true)
        context   {::core/postgres-datasource evaluator
                   ::core/database-evaluator evaluator
                   ::core/snapshot-reader store
                   ::core/revision-writer store}]
    (is (identical? evaluator (core/database-evaluator context)))
    (is (identical? store (core/snapshot-reader context)))
    (is (identical? store (core/revision-writer context)))
    (is (= [evaluator evaluator] (core/checkables context)))))

(deftest ready-test
  (testing "ready"
    (let [context {::core/postgres-datasource (checkable-evaluator true false)
                   ::core/database-evaluator   (checkable-evaluator true false)
                   ::core/snapshot-reader (database-store)
                   ::core/revision-writer (database-store)
                   :logger ::logger}]
      (is (healthcheck/ready? context))))
  (testing "not ready"
    (let [context {::core/postgres-datasource (checkable-evaluator true true)
                   ::core/database-evaluator   (checkable-evaluator false true)
                   ::core/snapshot-reader (database-store)
                   ::core/revision-writer (database-store)}]
      (is (not (healthcheck/ready? context))))))

(deftest ok-test
  (testing "ok"
    (let [context {::core/postgres-datasource (checkable-evaluator false true)
                   ::core/database-evaluator   (checkable-evaluator false true)
                   ::core/snapshot-reader (database-store)
                   ::core/revision-writer (database-store)
                   :logger ::logger}]
      (is (healthcheck/ok? context))))
  (testing "not ok"
    (let [context {::core/postgres-datasource (checkable-evaluator true false)
                   ::core/database-evaluator   (checkable-evaluator true true)
                   ::core/snapshot-reader (database-store)
                   ::core/revision-writer (database-store)}]
      (is (not (healthcheck/ok? context))))))
