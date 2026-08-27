(ns linear.handler.turso.push-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.handler.turso.push :as push]
   [linear.test :as test])
  (:import
   (java.util Arrays)))

(def ^:private open-condition
  {:type "not"
   :cond {:type "is_autocommit"}})

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

(deftest canonical-push-becomes-domain-statements
  (let [command (push/->command
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
    (is (= [nil 42 1.5 "hello"] (subvec parameters 0 4)))
    (is (Arrays/equals (byte-array [1 2]) (nth parameters 4)))))

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
    (let [data (test/catch-ex-data #(push/->command {:body-size 1024
                                                     :batch invalid}))]
      (is (= ::anomaly/incorrect (::anomaly/category data)))
      (is (= ::push/invalid-push-batch (:reason data))))))

(deftest enforces-fixed-push-limits
  (let [body (vec (repeat 1001 (step "UPDATE t SET x = 1" [])))]
    (is (= ::push/too-many-statements
           (:reason
             (test/catch-ex-data
               #(push/->command {:body-size 1024
                                 :batch (batch body)}))))))
  (is (= ::push/request-too-large
         (:reason
           (test/catch-ex-data
             #(push/->command {:body-size (inc (* 16 1024 1024))
                               :batch (batch [])}))))))

(deftest rejects-invalid-wire-values
  (doseq [value [{:type "integer" :value "9223372036854775808"}
                 {:type "float" :value ##Inf}
                 {:type "blob" :base64 "not base64"}
                 {:type "unknown" :value "x"}]]
    (is (= ::push/invalid-value
           (:reason
             (test/catch-ex-data
               #(push/->command
                  {:body-size 1024
                   :batch (batch [(step "SELECT ?" [value])])})))))))
