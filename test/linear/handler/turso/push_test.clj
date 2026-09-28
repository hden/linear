(ns linear.handler.turso.push-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [integrant.core :as ig]
   [linear.handler.turso.push :as push]
   [linear.test :as test]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction])
  (:import
   (java.io ByteArrayInputStream)
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

(deftest sync-metadata-query-requires-pull-permission
  (let [database (reify transaction/Transactable
                   (-transact [_ _ _]
                     (throw (ex-info "permission denied"
                                     {:cognitect.anomalies/category
                                      :cognitect.anomalies/forbidden}))))
        metadata-query {:steps [{:stmt {:sql "SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?"
                                        :want_rows true}}]}
        response ((push/handler {::core/database database})
                  {:identity {:sub "auth0|push-handler"}
                   :path-params {:id "d-test"}
                   :body-params {:requests [{:type "batch"
                                             :batch metadata-query}]}})]
    (is (= 403 (:status response)))))

(deftest canonical-push-becomes-domain-statements
  (let [command (push/command
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

(deftest canonical-push-removes-turso-metadata-and-retains-wire-indexes-at-boundary
  (let [parsed (push/parse-command
                 {:body-size 1024
                  :batch
                  (batch
                    [(step "CREATE TABLE IF NOT EXISTS turso_sync_last_change_id (client_id TEXT PRIMARY KEY, pull_gen INTEGER, change_id INTEGER)" [])
                     (step "UPDATE t SET value = ? WHERE id = ?"
                           [{:type "text" :value "from-js"}
                            {:type "integer" :value "1"}])
                     (step "INSERT INTO turso_sync_last_change_id(client_id, pull_gen, change_id) VALUES (?, ?, ?) ON CONFLICT(client_id) DO UPDATE SET pull_gen=excluded.pull_gen, change_id=excluded.change_id"
                            [{:type "text" :value "client"}
                             {:type "integer" :value "0"}
                             {:type "integer" :value "1"}])])})]
    (is (= ["UPDATE t SET value = ? WHERE id = ?"]
           (mapv :sql (get-in parsed [:command :statements]))))
    (is (= [2] (:wire-indexes parsed)))
    (is (nil? (get-in parsed [:command :statements 0 :wire-index])))))

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
    (let [data (test/catch-ex-data #(push/command {:body-size 1024
                                                   :batch invalid}))]
      (is (= ::anomaly/incorrect (::anomaly/category data)))
      (is (= ::push/invalid-push-batch (:reason data))))))

(deftest enforces-fixed-push-limits
  (let [body (vec (repeat 1001 (step "UPDATE t SET x = 1" [])))]
    (is (= ::push/too-many-statements
           (:reason
             (test/catch-ex-data
               #(push/command {:body-size 1024
                               :batch (batch body)}))))))
  (is (= ::push/request-too-large
         (:reason
           (test/catch-ex-data
             #(push/command {:body-size (inc (* 16 1024 1024))
                             :batch (batch [])}))))))

(deftest rejects-invalid-wire-values
  (doseq [value [{:type "integer" :value "9223372036854775808"}
                 {:type "float" :value ##Inf}
                 {:type "blob" :base64 "not base64"}
                 {:type "unknown" :value "x"}]]
    (is (= ::push/invalid-value
           (:reason
             (test/catch-ex-data
               #(push/command
                  {:body-size 1024
                   :batch (batch [(step "SELECT ?" [value])])})))))))

(deftest push-handler-rejects-an-invalid-content-length
  (let [response ((push/handler {})
                  {:headers {"content-length" "not-a-number"}
                   :body-params {:requests [{:type "batch"
                                             :batch (batch [])}]}})]
    (is (= 400 (:status response)))))

(deftest push-handler-rejects-a-content-length-over-the-limit
  (let [response ((push/handler {})
                  {:headers {"content-length" (str (inc (* 16 1024 1024)))}
                   :body-params {:requests [{:type "batch"
                                             :batch (batch [])}]}})]
    (is (= 400 (:status response)))))

(deftest request-body-limit-rejects-an-underreported-body
  (let [middleware (ig/init-key ::push/request-body-limit {})
        handler (middleware (fn [{:keys [body]}]
                              (while (not= -1 (.read ^java.io.InputStream body)))
                              {:status 200}))
        response (handler {:uri "/d/d-01M11GV3ER6E777ERMD0DK7CA1/v2/pipeline"
                           :body (ByteArrayInputStream.
                                   (byte-array (inc (* 16 1024 1024))))
                           :headers {"content-length" "1"}})]
    (is (= 400 (:status response)))))

(deftest request-body-limit-counts-read-overloads-and-allows-the-exact-limit
  (doseq [[read-body expected] [[(fn [^java.io.InputStream body]
                                   (loop [bytes []]
                                     (let [value (.read body)]
                                       (if (= -1 value)
                                         bytes
                                         (recur (conj bytes value))))))
                                 [1 2 3]]
                                [(fn [^java.io.InputStream body]
                                   (let [buffer (byte-array 3)]
                                     (.read body buffer)
                                     (vec buffer)))
                                 [1 2 3]]]]
    (let [handler (push/wrap-request-body-limit (fn [{:keys [body]}]
                                                  {:status 200 :body (read-body body)}) {:max-bytes 3})
          response (handler {:uri "/d/d-01M11GV3ER6E777ERMD0DK7CA1/v2/pipeline"
                             :body (ByteArrayInputStream. (byte-array [1 2 3]))})]
      (is (= 200 (:status response)))
      (is (= expected (:body response))))))

(deftest request-body-limit-propagates-unrelated-handler-errors
  (let [error   (ex-info "unrelated failure" {:reason ::unrelated-failure})
        handler (push/wrap-request-body-limit (fn [_] (throw error)) {:max-bytes 3})
        thrown  (try
                  (handler {:uri "/d/d-01M11GV3ER6E777ERMD0DK7CA1/v2/pipeline"})
                  (catch clojure.lang.ExceptionInfo exception exception))]
    (is (identical? error thrown))))
