(ns linear.adapter.sqlite.core
  (:require
   [cognitect.anomalies :as anomaly]))

(defn fault
  ([message data]
   (throw (ex-info message
                   (assoc data ::anomaly/category ::anomaly/fault))))
  ([message data cause]
   (throw (ex-info message
                   (assoc data ::anomaly/category ::anomaly/fault)
                   cause))))
