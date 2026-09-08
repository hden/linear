(ns linear.handler.turso.pull
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.handler.turso.protobuf :as protobuf]
   [linear.usecase.database :as database])
  (:import
   (java.io InputStream)))

(defn- body-bytes [body]
  (cond
    (bytes? body) body
    (instance? InputStream body) (.readAllBytes ^InputStream body)
    (nil? body) (byte-array 0)
    :else (throw (ex-info "Pull request body is not an input stream"
                          {::anomaly/category ::anomaly/incorrect}))))

(defn- error-response [error]
  {:status (if (#{::anomaly/incorrect ::anomaly/not-found}
                (::anomaly/category (ex-data error)))
             400
             500)
   :headers {"content-type" "application/octet-stream"}
   :body (.getBytes ^String (.getMessage ^Exception error) "UTF-8")})

(defn handler [context]
  (fn [{:keys [body path-params]}]
    (try
      ;; TODO(turso-sync): client-pages and server-query-selector are sent by
      ;; the official client for partial-sync query strategy. The official
      ;; CLI sync server does not implement these selectors yet.
      (let [pull (protobuf/decode-pull (body-bytes body))
            result  (database/pull
                      context
                      (:id path-params)
                      {:server-revision (not-empty (:server-revision pull))
                       :client-revision (not-empty (:client-revision pull))
                       :page-ids (protobuf/decode-page-selector
                                   (:server-pages-selector pull))})]
        {:status 200
         :headers {"content-type" "application/octet-stream"}
         :body (protobuf/pull-stream result)})
      (catch Exception error
        (error-response error)))))
