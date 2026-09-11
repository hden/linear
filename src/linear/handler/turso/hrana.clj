(ns linear.handler.turso.hrana
  (:require
   [cognitect.anomalies :as anomaly]))

(defn- content-type []
  {"content-type" "application/json"})

(def ^:private last-change-id-query
  "SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?")

(defn sync-metadata-statement? [{:keys [sql]}]
  (re-find #"(?i)^(CREATE TABLE IF NOT EXISTS|INSERT INTO|UPDATE)\s+\"?turso_sync_last_change_id\"?(?:\s|\(|$)"
           sql))

(defn last-change-id-query? [batch]
  (let [statement (get-in batch [:steps 0 :stmt])]
    (and (= 1 (count (:steps batch)))
         (= last-change-id-query (:sql statement))
         (= true (:want_rows statement)))))

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
  {:status 200
   :headers (content-type)
   :body {:baton nil
          :base_url nil
          :results [{:type "ok"
                     :response (batch-result (vec (repeat step-count
                                                          (ok-step-result)))
                                             (vec (repeat step-count nil)))}]}})

(defn last-change-id-response []
  {:status 200
   :headers (content-type)
   :body {:baton nil
          :base_url nil
          :results [{:type "ok"
                     :response
                     (batch-result [{:cols [{:name "pull_gen"
                                             :decltype "INTEGER"}
                                            {:name "change_id"
                                             :decltype "INTEGER"}]
                                     :rows []
                                     :affected_row_count 0
                                     :last_insert_rowid nil
                                     :replication_index nil
                                     :rows_read 0
                                     :rows_written 0
                                     :query_duration_ms 0.0}]
                                   [nil])}]}})

(defn single-batch [pipeline]
  (let [batches (keep #(when (= "batch" (:type %)) (:batch %))
                      (:requests pipeline))]
    (if (= 1 (count batches))
      (first batches)
      (throw (ex-info "Push pipeline must contain exactly one batch"
                       {::anomaly/category ::anomaly/incorrect
                        :reason ::invalid-pipeline})))))

(defn- error-message [error]
  (loop [current error]
    (if-let [message (:sqlite-message (ex-data current))]
      message
      (if-let [cause (.getCause ^Throwable current)]
        (recur cause)
        (.getMessage ^Exception error)))))

(defn- error-data [error]
  (loop [current error]
    (if-let [data (ex-data current)]
      (if (contains? data :statement-index)
        data
        (if-let [cause (.getCause ^Throwable current)]
          (recur cause)
          data))
      (if-let [cause (.getCause ^Throwable current)]
        (recur cause)
        {}))))

(defn statement-error? [error]
  (contains? (error-data error) :statement-index))

(defn batch-error-response
  [batch {:keys [error wire-indexes]}]
  (let [step-count  (count (:steps batch))
        domain-index (or (:statement-index (error-data error)) 0)
        error-index (if wire-indexes
                      (get wire-indexes domain-index domain-index)
                      domain-index)
        step-results (assoc (vec (repeat step-count (ok-step-result)))
                            error-index
                            nil)
        step-errors (assoc (vec (repeat step-count nil))
                           error-index
                           {:message (error-message error)
                            :code "BATCH_STEP_ERROR"})]
    {:status 200
     :headers (content-type)
     :body {:baton nil
            :base_url nil
            :results [{:type "ok"
                       :response (batch-result step-results
                                               step-errors)}]}}))

(defn error-response [error]
  (let [category (::anomaly/category (ex-data error))
        status   (case category
                   ::anomaly/incorrect 400
                   ::anomaly/conflict 409
                   500)]
    {:status status
     :headers (content-type)
     :body {:baton nil
            :base_url nil
            :results [{:type "error"
                       :error {:message (error-message error)}}]}}))
