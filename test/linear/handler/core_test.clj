(ns linear.handler.core-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.handler.core :as core]))

(deftest outer-anomaly-fields-take-precedence-over-causes
  (let [cause (ex-info "native error" {::anomaly/category ::anomaly/incorrect
                                       ::anomaly/message "native detail"
                                       :reason ::native-reason
                                       :statement-index 2})
        error (ex-info "outer error" {::anomaly/category ::anomaly/fault
                                      ::anomaly/message "outer detail"
                                      :reason ::outer-reason
                                      :statement-index 0} cause)]
    (is (= {::anomaly/category ::anomaly/fault
            ::anomaly/message "outer detail"
            :reason ::outer-reason
            :statement-index 0}
           (core/anomaly error)))))

(deftest wrapped-statement-errors-retain-domain-context-and-native-detail
  (let [cause (ex-info "statement failed" {::anomaly/message "UNIQUE constraint failed"
                                           :reason ::constraint-error})
        error (ex-info "evaluation failed" {::anomaly/category ::anomaly/fault
                                            :statement-index 0} cause)]
    (is (= {::anomaly/category ::anomaly/fault
            ::anomaly/message "UNIQUE constraint failed"
            :reason ::constraint-error
            :statement-index 0}
           (core/anomaly error)))))

(deftest unclassified-exceptions-use-fault-and-the-outer-message
  (is (= {:status 500 :body {:error "unexpected failure"}}
         (core/error-response (core/anomaly (Exception. "unexpected failure")))))
  (is (= {::anomaly/category ::anomaly/fault ::anomaly/message "wrapper"}
         (core/anomaly (Exception. "wrapper" (Exception. "cause"))))))
