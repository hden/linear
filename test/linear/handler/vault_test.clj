(ns linear.handler.vault-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.vault :as handler]
   [ring.mock.request :refer [request]]))

(deftest create-handler-rejects-a-missing-idempotency-header
  (is (= 400
         (:status ((handler/create {})
                   (request :post "/control/v1/vaults"))))))
