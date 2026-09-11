(ns linear.handler.turso.hrana-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.turso.hrana :as hrana]))

(deftest extracts-one-batch-from-a-pipeline
  (let [batch {:steps []}]
    (is (= batch
           (hrana/single-batch {:requests [{:type "batch"
                                            :batch batch}]})))))

(deftest successful-batch-response-preserves-step-count
  (let [response (hrana/batch-response 2)]
    (is (= 200 (:status response)))
    (is (= "ok" (get-in response [:body :results 0 :type])))
    (is (= 2 (count (get-in response [:body :results 0 :response
                                      :result :step_results]))))
    (is (= [nil nil]
           (get-in response [:body :results 0 :response
                             :result :step_errors])))))

(deftest recognizes-the-sync-metadata-query
  (is (hrana/last-change-id-query?
        {:steps [{:stmt {:sql "SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?"
                         :want_rows true}}]})))

(deftest sync-metadata-response-reports-an-unknown-client-as-an-empty-result
  (is (empty?
        (get-in (hrana/last-change-id-response)
                [:body :results 0 :response :result :step_results 0 :rows]))))

(deftest malformed-pipeline-response-is-an-http-client-error
  (is (= 400
         (:status (hrana/error-response
                    (ex-info "invalid" {:cognitect.anomalies/category
                                        :cognitect.anomalies/incorrect}))))))

(deftest error-response-preserves-sqlite-diagnostics
  (let [error (ex-info "SQLite execution failed"
                       {}
                       (ex-info "SQLite statement failed"
                                {:sqlite-message "UNIQUE constraint failed: u.y"}))]
    (is (= "UNIQUE constraint failed: u.y"
           (get-in (hrana/error-response error)
                   [:body :results 0 :error :message])))))

(deftest batch-error-response-uses-step-errors
  (let [error (ex-info "SQLite execution failed"
                       {}
                       (ex-info "SQLite statement failed"
                                {:sqlite-message "UNIQUE constraint failed: u.y"
                                 :statement-index 2}))
        response (hrana/batch-error-response {:steps [{} {} {}]} {:error error})]
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
        response (hrana/batch-error-response {:steps [{} {} {}]} {:error error :wire-indexes [2]})]
    (is (= [true true false]
           (mapv some?
                 (get-in response [:body :results 0 :response :result :step_results]))))
    (is (= [nil
            nil
            {:message "SQLite execution failed"
             :code "BATCH_STEP_ERROR"}]
           (get-in response [:body :results 0 :response :result :step_errors])))))
