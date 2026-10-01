(ns linear.handler.turso.push-test
  (:require
   [clojure.test :refer [deftest is]]
   [integrant.core :as ig]
   [linear.handler.turso.push :as push]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction])
  (:import
   (java.io ByteArrayInputStream)))

(def ^:private progress-query
  "SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?")

(defn- batch []
  {:steps [{:condition nil
            :stmt {:sql "BEGIN IMMEDIATE" :sql_id nil :args [] :named_args []
                   :want_rows false :replication_index nil}}
           {:condition {:type "not" :cond {:type "is_autocommit"}}
            :stmt {:sql "COMMIT" :sql_id nil :args [] :named_args []
                   :want_rows false :replication_index nil}}]
   :replication_index nil})

(deftest sync-metadata-query-requires-pull-permission
  (let [database (reify transaction/Transactable
                   (-transact [_ _ _]
                     (throw (ex-info "permission denied"
                                     {:cognitect.anomalies/category
                                      :cognitect.anomalies/forbidden}))))
        metadata-query {:steps [{:stmt {:sql progress-query
                                        :args [{:type "text" :value "client"}]
                                        :want_rows true}}]}
        response ((push/handler {::core/database database})
                  {:identity {:sub "auth0|push-handler"}
                   :path-params {:id "d-test"}
                   :body-params {:requests [{:type "batch"
                                             :batch metadata-query}]}})]
    (is (= 403 (:status response)))))

(deftest malformed-pipeline-and-metadata-arguments-return-client-errors
  (doseq [pipeline [{:requests 1}
                    {:requests [{:type "batch"
                                 :batch {:steps [{:stmt {:sql progress-query
                                                         :want_rows true :args 1}}]}}]}]]
    (is (= 400 (:status ((push/handler {}) {:body-params pipeline}))))))

(deftest push-handler-rejects-an-invalid-content-length
  (let [response ((push/handler {})
                  {:headers {"content-length" "not-a-number"}
                   :body-params {:requests [{:type "batch"
                                             :batch (batch)}]}})]
    (is (= 400 (:status response)))))

(deftest push-handler-rejects-a-content-length-over-the-limit
  (let [response ((push/handler {})
                  {:headers {"content-length" (str (inc (* 16 1024 1024)))}
                   :body-params {:requests [{:type "batch"
                                             :batch (batch)}]}})]
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
