(ns linear.handler.turso.pull
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.handler.turso.protobuf :as protobuf]
   [linear.usecase.database :as database])
  (:import
   (java.io InputStream)))

(def ^:private ^:const max-request-bytes (* 16 1024 1024))

(defn- body-bytes [body]
  (let [data (cond
               (bytes? body) body
               (instance? InputStream body) (.readNBytes ^InputStream body (inc max-request-bytes))
               (nil? body) (byte-array 0)
               :else (throw (ex-info "Pull request body is not an input stream"
                                     {::anomaly/category ::anomaly/incorrect})))]
    (when (> (alength ^bytes data) max-request-bytes)
      (throw (ex-info "Pull request is too large"
                      {::anomaly/category ::anomaly/incorrect
                       :reason ::request-too-large})))
    data))

(defn- error-response [error]
  {:status (case (::anomaly/category (ex-data error))
             ::anomaly/incorrect 400
             ::anomaly/not-found 400
             ::anomaly/forbidden 403
             ::anomaly/conflict 409
             500)
   :headers {"content-type" "application/octet-stream"}
   :body (.getBytes ^String (.getMessage ^Exception error) "UTF-8")})

(defn handler [context]
  (fn [{:keys [body identity path-params]}]
    (try
      ;; TODO(turso-sync): client-pages and server-query-selector are sent by
      ;; the official client for partial-sync query strategy. The official
      ;; CLI sync server does not implement these selectors yet.
      (let [pull (protobuf/decode-pull (body-bytes body))
            result  (database/pull context {:server-revision (not-empty (:server-revision pull))
                                            :client-revision (not-empty (:client-revision pull))
                                            :page-ids (protobuf/decode-page-selector
                                                        (:server-pages-selector pull))
                                            :actor (:sub identity)
                                            :database-id (:id path-params)})]
        {:status 200
         :headers {"content-type" "application/octet-stream"}
         :body (protobuf/pull-stream result)})
      (catch Exception error
        (error-response error)))))
