(ns linear.handler.turso.push
  (:require
   [cognitect.anomalies :as anomaly]
   [integrant.core :as ig]
   [linear.handler.core :as core]
   [linear.handler.turso.hrana :as hrana]
   [linear.handler.turso.request :as turso-request]
   [linear.usecase.database :as database])
  (:import
   (java.io FilterInputStream InputStream)))

(defn- invalid [message reason]
  (throw (ex-info message
                  {::anomaly/category ::anomaly/incorrect
                   ::anomaly/message message
                   :reason reason})))

(defn- request-content-length [request]
  (let [value (get-in request [:headers "content-length"])]
    (cond
      (string? value) (try
                        (Long/parseLong value)
                        (catch NumberFormatException _
                          (invalid "Content-Length is invalid"
                                    ::invalid-content-length)))
      :else 0)))

(defn- request-too-large []
  (invalid "Push request is too large" ::turso-request/request-too-large))

(defn- limited-input-stream [^InputStream input max-bytes]
  (let [read-bytes (atom 0)]
    (proxy [FilterInputStream] [input]
      (read
        ([]
         (let [result (.read input)]
           (if (= -1 result)
             -1
             (if (< @read-bytes max-bytes)
               (do (swap! read-bytes inc) result)
               (request-too-large)))))
        ([^bytes bytes]
         (.read ^InputStream this bytes 0 (alength bytes)))
        ([^bytes bytes ^long offset ^long length]
         (if (zero? length)
           0
           (let [remaining (- max-bytes @read-bytes)]
             (if (zero? remaining)
               (let [result (.read input)]
                 (if (= -1 result)
                   -1
                   (request-too-large)))
               (let [result (.read input bytes offset (min length remaining))]
                 (when (pos? result)
                   (swap! read-bytes + result))
                 result)))))))))

(defn- push-request? [request]
  (re-matches #"/d/[^/]+/v2/pipeline" (:uri request)))

(defn wrap-request-body-limit [handler {:keys [max-bytes]}]
  (fn [{:keys [body] :as request}]
    (try
      (handler (if (and (push-request? request)
                        (instance? InputStream body))
                 (assoc request :body (limited-input-stream body max-bytes))
                 request))
      (catch clojure.lang.ExceptionInfo error
        (if (= ::turso-request/request-too-large (:reason (ex-data error)))
          (hrana/error-response (core/anomaly error))
          (throw error))))))

(defmethod ig/init-key ::request-body-limit [_ _]
  #(wrap-request-body-limit % {:max-bytes turso-request/max-request-bytes}))

(defn- handle-batch [context request batch]
  (let [{:keys [command wire-indexes]}
        (turso-request/parse-command {:body-size (request-content-length request)
                                      :batch batch})]
    (try
      (database/push! context {:actor (get-in request [:identity :sub])
                               :database-id (:id (:path-params request))
                               :command command})
      (hrana/batch-response (count (:steps batch)))
      (catch Exception error
        (let [anomaly (core/anomaly error)]
          (if (contains? anomaly :statement-index)
            (hrana/batch-error-response batch {:anomaly anomaly :wire-indexes wire-indexes})
            (throw error)))))))

(defn handler [context]
  (fn [{:keys [body-params] :as request}]
    (try
      (let [batch (turso-request/single-batch body-params)]
        (if (turso-request/last-change-id-query? batch)
          (hrana/last-change-id-response
            (database/sync-progress context
              {:actor (get-in request [:identity :sub])
               :database-id (get-in request [:path-params :id])
               :client-id (turso-request/metadata-client-id batch)}))
          (handle-batch context request batch)))
      (catch Exception error
        (hrana/error-response (core/anomaly error))))))
