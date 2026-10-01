(ns linear.handler.turso.hrana
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.handler.core :as core]))

(defn- pipeline-response [status result]
  {:status status
   :headers {"content-type" "application/json"}
   :body {:baton nil
          :base_url nil
          :results [result]}})

(defn- ok-step-result []
  {:cols []
   :rows []
   :affected_row_count 0
   :last_insert_rowid nil
   :replication_index nil
   :rows_read 0
   :rows_written 0
   :query_duration_ms 0.0})

(defn- batch-result [step-results step-errors]
  {:type "batch"
   :result {:step_results step-results
            :step_errors step-errors
            :replication_index nil}})

(defn batch-response [step-count]
  (pipeline-response 200
                     {:type "ok"
                      :response (batch-result (vec (repeat step-count
                                                           (ok-step-result)))
                                              (vec (repeat step-count nil)))}))

(defn last-change-id-response [{:keys [generation change-id] :as progress}]
  (pipeline-response 200
    {:type "ok"
     :response
     (batch-result [{:cols [{:name "pull_gen"
                             :decltype "INTEGER"}
                            {:name "change_id"
                             :decltype "INTEGER"}]
                     :rows (if progress
                             [[{:type "integer" :value (str generation)}
                               {:type "integer" :value (str change-id)}]]
                             [])
                     :affected_row_count 0
                     :last_insert_rowid nil
                     :replication_index nil
                     :rows_read 0
                     :rows_written 0
                     :query_duration_ms 0.0}]
                   [nil])}))

(defn batch-error-response
  [{:as batch} {:keys [anomaly wire-indexes]}]
  (let [step-count  (count (:steps batch))
        domain-index (or (:statement-index anomaly) 0)
        error-index (if wire-indexes
                      (get wire-indexes domain-index domain-index)
                      domain-index)
        commit-index (dec step-count)
        step-results (into (vec (repeat error-index (ok-step-result)))
                           (repeat (- step-count error-index) nil))
        step-errors (assoc (vec (repeat step-count nil))
                           commit-index
                           {:message "Transaction rolled back"
                            :code "TRANSACTION_ROLLED_BACK"}
                           error-index
                           {:message (::anomaly/message anomaly)
                            :code "BATCH_STEP_ERROR"})]
    (pipeline-response 200
                       {:type "ok"
                        :response (batch-result step-results
                                                step-errors)})))

(defn error-response [{::anomaly/keys [category message] :as arg-map}]
  (pipeline-response (if (= ::anomaly/not-found category) 500 (core/http-status arg-map))
                     {:type "error"
                      :error {:message message
                              :code (case category
                                      ::anomaly/incorrect "INVALID_REQUEST"
                                      ::anomaly/forbidden "FORBIDDEN"
                                      ::anomaly/conflict "CONFLICT"
                                      "INTERNAL_ERROR")}}))
