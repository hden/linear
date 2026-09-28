(ns linear.handler.turso.pull-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.handler.turso.pull :as pull]
   [linear.test :as test]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction])
  (:import
   (java.io ByteArrayInputStream InputStream)))

(deftest pull-handler-rejects-a-body-with-an-unsupported-type
  (let [response ((pull/handler {}) {:body "not-an-input-stream"})]
    (is (= 400 (:status response)))))

(deftest pull-handler-translates-backend-anomalies
  (doseq [[category status] [[:cognitect.anomalies/incorrect 400]
                             [:cognitect.anomalies/not-found 400]
                             [:cognitect.anomalies/forbidden 403]
                             [:cognitect.anomalies/unavailable 500]
                             [:cognitect.anomalies/fault 500]]]
    (let [database (reify transaction/Transactable
                     (-transact [_ _ _]
                       (throw (ex-info "backend failed"
                                       {:cognitect.anomalies/category category}))))
          response ((pull/handler {::core/database database})
                    {:body (byte-array 0)
                     :identity {:sub "auth0|pull-handler"}
                     :path-params {:id "d-test"}})]
      (is (= status (:status response))))))

(def ^:private body-limit (* 16 1024 1024))

(defn- padded-pull-request [size]
  ;; One unknown length-delimited protobuf field keeps boundary requests valid.
  (let [data (byte-array size)
        payload-size (- size 5)]
    (aset-byte data 0 (byte 0x7a))
    (doseq [index (range 4)]
      (aset-byte data (inc index)
                 (unchecked-byte
                   (bit-or (if (< index 3) 0x80 0)
                           (bit-and 0x7f (unsigned-bit-shift-right payload-size (* index 7)))))))
    data))

(defn- observed-stream [data]
  (let [input (ByteArrayInputStream. data)
        bytes-read (atom 0)
        closed? (atom false)]
    {:stream (proxy [InputStream] []
               (read
                 ([]
                  (let [value (.read input)]
                    (when (not= -1 value) (swap! bytes-read inc))
                    value))
                 ([buffer offset length]
                  (let [amount (.read input buffer offset length)]
                    (when (pos? amount) (swap! bytes-read + amount))
                    amount)))
               (close [] (reset! closed? true)))
     :bytes-read bytes-read
     :closed? closed?}))

(defn- observed-context [calls]
  {::core/database
   (reify transaction/Transactable
     (-transact [_ _ _]
       (swap! calls inc)
       (throw (ex-info "backend reached" {::anomaly/category ::anomaly/fault}))))})

(deftest oversized-pull-bodies-are-rejected-before-usecase-execution
  (let [data (padded-pull-request (+ body-limit 100))
        {:keys [stream bytes-read closed?]} (observed-stream data)]
    (with-open [stream stream]
      (doseq [body [data stream]]
        (let [calls (atom 0)
              response ((pull/handler (observed-context calls))
                        {:body body
                         :headers {"content-length" "1"}
                         :path-params {:id "d-test"}})]
          (is (= 400 (:status response)))
          (is (= "application/octet-stream" (get-in response [:headers "content-type"])))
          (is (= "Pull request is too large" (String. ^bytes (:body response) "UTF-8")))
          (is (zero? @calls))))
      (is (= (inc body-limit) @bytes-read))
      (is (false? @closed?)))))

(deftest pull-bodies-at-the-limit-reach-the-usecase
  (let [data (padded-pull-request body-limit)
        {:keys [stream bytes-read closed?]} (observed-stream data)]
    (with-open [stream stream]
      (doseq [body [data stream]]
        (let [calls (atom 0)
              response ((pull/handler (observed-context calls))
                        {:body body :path-params {:id "d-test"}})]
          (is (= "backend reached" (String. ^bytes (:body response) "UTF-8")))
          (is (= 1 @calls))))
      (is (= body-limit @bytes-read))
      (is (false? @closed?)))))

(deftest oversized-pull-body-has-an-incorrect-anomaly
  (is (= {::anomaly/category ::anomaly/incorrect
          :reason ::pull/request-too-large}
         (test/catch-ex-data #(#'pull/body-bytes (byte-array (inc body-limit)))))))
