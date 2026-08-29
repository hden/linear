(ns linear.spec-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.spec :as spec]
   [linear.usecase.database]
   [malli.core :as m]))

(deftest source-registry-resolves-domain-schemas
  (is (true? (m/validate
               :linear.usecase.database/page-id
               1
               {:registry spec/registry})))
  (is (false? (m/validate
                :linear.usecase.database/page-id
                0
                {:registry spec/registry}))))
