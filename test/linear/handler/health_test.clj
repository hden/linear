(ns linear.handler.health-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.health :as health]
   [linear.protocol :as protocol]
   [linear.usecase.database :as database]
   [ring.mock.request :refer [request]]))

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

(def ^:private healthy-context
  {:linear.usecase.core/postgres-datasource (checkable-evaluator true true)
   :linear.usecase.core/database-evaluator (checkable-evaluator true true)
   :linear.usecase.core/snapshot-reader (database-store)
   :linear.usecase.core/revision-writer (database-store)})

(deftest readiness-handler
  (let [f (health/ready healthy-context)]
    (is (= {:status 200 :body {:status "ready"}}
           (f (request :get "/health/ready")))))
  (let [context (assoc healthy-context
                       :linear.usecase.core/postgres-datasource
                       (checkable-evaluator false true))]
    (is (= {:status 503 :body {:status "not ready"}}
           ((health/ready context) (request :get "/health/ready"))))))

(deftest liveness-handler
  (is (= {:status 200 :body {:status "ok"}}
         ((health/ok healthy-context) (request :get "/health/ok"))))
  (let [context (assoc healthy-context
                       :linear.usecase.core/database-evaluator
                       (checkable-evaluator true false))]
    (is (= {:status 500 :body {:status "not ok"}}
           ((health/ok context) (request :get "/health/ok"))))))
