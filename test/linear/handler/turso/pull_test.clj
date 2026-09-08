(ns linear.handler.turso.pull-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.turso.pull :as pull]))

(deftest pull-handler-rejects-a-body-with-an-unsupported-type
  (let [response ((pull/handler {}) {:body "not-an-input-stream"})]
    (is (= 400 (:status response)))))
