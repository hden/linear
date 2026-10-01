(ns linear.handler.turso.request-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.handler.turso.request :as request]
   [linear.test :as test])
  (:import
   (java.util Arrays)))

(def ^:private open-condition
  {:type "not"
   :cond {:type "is_autocommit"}})

(def ^:private progress-upsert
  "INSERT INTO turso_sync_last_change_id(client_id, pull_gen, change_id) VALUES (?, ?, ?) ON CONFLICT(client_id) DO UPDATE SET pull_gen=excluded.pull_gen, change_id=excluded.change_id")

(defn- statement [sql args]
  {:sql sql
   :sql_id nil
   :args args
   :named_args []
   :want_rows false
   :replication_index nil})

(defn- step [sql args]
  {:condition open-condition
   :stmt (statement sql args)})

(defn- batch [body]
  {:steps (into [{:condition nil
                  :stmt (statement "BEGIN IMMEDIATE" [])}]
                (concat body [(step "COMMIT" [])]))
   :replication_index nil})

(defn- command [arg-map]
  (:command (request/parse-command arg-map)))

(deftest canonical-push-becomes-domain-statements
  (let [command (command
                  {:body-size 1024
                   :batch
                   (batch
                     [(step "INSERT INTO t VALUES (?, ?, ?, ?, ?)"
                            [{:type "null"}
                             {:type "integer" :value "42"}
                             {:type "float" :value 1.5}
                             {:type "text" :value "hello"}
                             {:type "blob" :base64 "AQI="}])])})
        parameters (get-in command [:statements 0 :parameters])]
    (is (= "INSERT INTO t VALUES (?, ?, ?, ?, ?)"
           (get-in command [:statements 0 :sql])))
    (is (nil? (get-in command [:statements 0 :wire-index])))
    (is (= [nil 42 1.5 "hello"] (subvec parameters 0 4)))
    (is (Arrays/equals (byte-array [1 2]) (nth parameters 4)))))

(deftest canonical-push-retains-metadata-and-extracts-sync-progress
  (let [parsed (request/parse-command
                 {:body-size 1024
                  :batch
                  (batch
                    [(step "CREATE TABLE IF NOT EXISTS turso_sync_last_change_id (client_id TEXT PRIMARY KEY, pull_gen INTEGER, change_id INTEGER)" [])
                     (step "UPDATE t SET value = ? WHERE id = ?"
                           [{:type "text" :value "from-js"}
                            {:type "integer" :value "1"}])
                     (step progress-upsert
                       [{:type "text" :value "client"}
                        {:type "integer" :value "0"}
                        {:type "integer" :value "1"}])])})]
    (is (= 3 (count (get-in parsed [:command :statements]))))
    (is (= {:client-id "client" :generation 0 :change-id 1}
           (get-in parsed [:command :sync-progress])))
    (is (= [1 2 3] (:wire-indexes parsed)))
    (is (nil? (get-in parsed [:command :statements 0 :wire-index])))))

(deftest sync-progress-rejects-invalid-identities-and-counter-types
  (let [valid-args [{:type "text" :value "client"}
                    {:type "integer" :value "0"}
                    {:type "integer" :value "1"}]]
    (doseq [[case-name index invalid-value]
            [[:empty-client-id 0 {:type "text" :value ""}]
             [:numeric-client-id 0 {:type "integer" :value "1"}]
             [:text-generation 1 {:type "text" :value "0"}]
             [:negative-generation 1 {:type "integer" :value "-1"}]]]
      (is (= ::anomaly/incorrect
             (::anomaly/category
               (test/catch-ex-data
                 #(command
                    {:body-size 1024
                     :batch (batch [(step progress-upsert
                                      (assoc valid-args index invalid-value))])}))))
          (name case-name)))))

(deftest rejects-noncanonical-transaction-shapes
  (doseq [invalid
          [(assoc-in (batch [(step "UPDATE t SET x = 1" [])])
                     [:steps 0 :stmt :sql]
                     "BEGIN")
           (assoc-in (batch [(step "UPDATE t SET x = 1" [])])
                     [:steps 1 :condition]
                     nil)
           (assoc-in (batch [(step "UPDATE t SET x = 1" [])])
                     [:steps 1 :stmt :want_rows]
                     true)
           (assoc-in (batch [(step "UPDATE t SET x = 1" [])])
                     [:steps 1 :stmt :named_args]
                     [{:name "x" :value {:type "integer" :value "1"}}])
           {:steps [(step "SELECT 1" [])]}]]
    (let [data (test/catch-ex-data #(command {:body-size 1024
                                              :batch invalid}))]
      (is (= ::anomaly/incorrect (::anomaly/category data)))
      (is (= ::request/invalid-push-batch (:reason data))))))

(deftest enforces-fixed-push-limits
  (let [body (vec (repeat 1001 (step "UPDATE t SET x = 1" [])))]
    (is (= ::request/too-many-statements
           (:reason
             (test/catch-ex-data
               #(command {:body-size 1024
                          :batch (batch body)}))))))
  (is (= ::request/request-too-large
         (:reason
           (test/catch-ex-data
             #(command {:body-size (inc (* 16 1024 1024))
                        :batch (batch [])}))))))

(deftest rejects-invalid-wire-values
  (doseq [value [{:type "integer" :value "9223372036854775808"}
                 {:type "float" :value ##Inf}
                 {:type "blob" :base64 "not base64"}
                 {:type "unknown" :value "x"}]]
    (is (= ::request/invalid-value
           (:reason
             (test/catch-ex-data
               #(command
                  {:body-size 1024
                   :batch (batch [(step "SELECT ?" [value])])})))))))

(deftest extracts-one-batch-from-a-pipeline
  (let [batch {:steps []}]
    (is (= batch
           (request/single-batch {:requests [{:type "batch"
                                              :batch batch}]})))))

(deftest pipeline-rejects-extra-requests-and-non-null-batons
  (doseq [pipeline [{:requests [{:type "execute"} {:type "batch" :batch {:steps []}}]}
                    {:baton "old-stream" :requests [{:type "batch" :batch {:steps []}}]}]]
    (is (thrown? clojure.lang.ExceptionInfo (request/single-batch pipeline)))))

(deftest recognizes-the-sync-metadata-query
  (is (request/last-change-id-query?
        {:steps [{:stmt {:sql "SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?"
                         :want_rows true}}]})))
