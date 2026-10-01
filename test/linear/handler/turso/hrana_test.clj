(ns linear.handler.turso.hrana-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.handler.core :as core]
   [linear.handler.turso.hrana :as hrana]))

(deftest rolled-back-batch-reports-the-failed-commit-and-unexecuted-steps
  (let [response (hrana/batch-error-response
                   {:steps [{} {} {} {} {}]}
                   {:anomaly (core/anomaly (ex-info "duplicate column name: x" {:statement-index 1}))
                    :wire-indexes [1 2 3]})
        result (get-in response [:body :results 0 :response :result])]
    (is (= [true true false false false] (mapv some? (:step_results result))))
    (is (= "duplicate column name: x" (get-in result [:step_errors 2 :message])))
    (is (= "TRANSACTION_ROLLED_BACK" (get-in result [:step_errors 4 :code])))
    (is (nil? (get-in result [:step_errors 3])))))

(deftest successful-batch-response-preserves-step-count
  (let [response (hrana/batch-response 2)]
    (is (= 200 (:status response)))
    (is (= {"content-type" "application/json"} (:headers response)))
    (is (= {:baton nil :base_url nil}
           (dissoc (:body response) :results)))
    (is (= 1 (count (get-in response [:body :results]))))
    (is (= "ok" (get-in response [:body :results 0 :type])))
    (is (= 2 (count (get-in response [:body :results 0 :response
                                      :result :step_results]))))
    (is (= [nil nil]
           (get-in response [:body :results 0 :response
                             :result :step_errors])))))

(deftest sync-metadata-response-reports-an-unknown-client-as-an-empty-result
  (is (empty?
        (get-in (hrana/last-change-id-response nil)
                [:body :results 0 :response :result :step_results 0 :rows]))))

(deftest sync-metadata-response-encodes-the-full-integer-range-as-strings
  (is (= [[{:type "integer" :value "2"}
           {:type "integer" :value "9223372036854775807"}]]
         (get-in (hrana/last-change-id-response {:generation 2 :change-id 9223372036854775807})
                 [:body :results 0 :response :result :step_results 0 :rows]))))

(deftest malformed-pipeline-response-is-an-http-client-error
  (is (= 400
         (:status (hrana/error-response
                    (core/anomaly (ex-info "invalid" {:cognitect.anomalies/category
                                                      :cognitect.anomalies/incorrect})))))))

(deftest error-response-preserves-sqlite-diagnostics
  (let [error (ex-info "SQLite execution failed"
                       {}
                       (ex-info "SQLite statement failed"
                                {::anomaly/message "UNIQUE constraint failed: u.y"}))]
    (is (= "UNIQUE constraint failed: u.y"
           (get-in (hrana/error-response (core/anomaly error))
                   [:body :results 0 :error :message])))))

(deftest batch-error-response-uses-step-errors
  (let [error (ex-info "SQLite execution failed"
                       {}
                       (ex-info "SQLite statement failed"
                                {::anomaly/message "UNIQUE constraint failed: u.y"
                                 :statement-index 2}))
        response (hrana/batch-error-response {:steps [{} {} {}]} {:anomaly (core/anomaly error)})]
    (is (= 200 (:status response)))
    (is (= [true true false]
           (mapv some?
                 (get-in response [:body :results 0 :response :result :step_results]))))
    (is (= [nil
            nil
            {:message "UNIQUE constraint failed: u.y"
             :code "BATCH_STEP_ERROR"}]
           (get-in response [:body :results 0 :response :result :step_errors])))))

(deftest batch-error-response-maps-domain-index-back-to-wire-index
  (let [error (ex-info "SQLite execution failed"
                       {}
                       (ex-info "SQLite statement failed"
                                {:statement-index 0}))
        response (hrana/batch-error-response {:steps [{} {} {}]} {:anomaly (core/anomaly error) :wire-indexes [2]})]
    (is (= [true true false]
           (mapv some?
                 (get-in response [:body :results 0 :response :result :step_results]))))
    (is (= [nil
            nil
            {:message "SQLite execution failed"
             :code "BATCH_STEP_ERROR"}]
           (get-in response [:body :results 0 :response :result :step_errors])))))

(deftest pipeline-anomalies-preserve-protocol-status-and-codes
  (doseq [[category status code]
          [[::anomaly/incorrect 400 "INVALID_REQUEST"]
           [::anomaly/forbidden 403 "FORBIDDEN"]
           [::anomaly/conflict 409 "CONFLICT"]
           [::anomaly/not-found 500 "INTERNAL_ERROR"]
           [::anomaly/unavailable 500 "INTERNAL_ERROR"]
           [::anomaly/fault 500 "INTERNAL_ERROR"]]]
    (let [response (hrana/error-response {::anomaly/category category
                                          ::anomaly/message "protocol detail"})]
      (is (= status (:status response)))
      (is (= {:message "protocol detail" :code code}
             (get-in response [:body :results 0 :error]))))))
