(ns linear.handler.turso.pull-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.turso.pull :as pull]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction]))

(deftest pull-handler-rejects-a-body-with-an-unsupported-type
  (let [response ((pull/handler {}) {:body "not-an-input-stream"})]
    (is (= 400 (:status response)))))

(deftest pull-handler-translates-backend-anomalies
  (doseq [[category status] [[:cognitect.anomalies/incorrect 400]
                             [:cognitect.anomalies/not-found 400]
                             [:cognitect.anomalies/unavailable 500]
                             [:cognitect.anomalies/fault 500]]]
    (let [database (reify transaction/Transactable
                     (-transact [_ _ _]
                       (throw (ex-info "backend failed"
                                       {:cognitect.anomalies/category category}))))
          response ((pull/handler {::core/database database})
                    {:body (byte-array 0) :path-params {:id "d-test"}})]
      (is (= status (:status response))))))
