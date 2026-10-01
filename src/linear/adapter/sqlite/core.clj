(ns linear.adapter.sqlite.core
  (:require
   [cognitect.anomalies :as anomaly]))

(defn fault
  ([message data]
   (fault message data nil))
  ([message data cause]
   (throw (ex-info message
                   (assoc data
                          ::anomaly/category ::anomaly/fault
                          ::anomaly/message  (or (::anomaly/message data)
                                                 (::anomaly/message (ex-data cause))
                                                 message))
                   cause))))
