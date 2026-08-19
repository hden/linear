(ns linear.adapter.database.core
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.database.schema :as schema]
   [malli.core :as m]))

(defprotocol Connection
  (-query [conn arg-map])
  (-transact! [conn arg-map]))

(defn- validate! [schema arg-map]
  (when-not (m/validate schema arg-map)
    (throw (ex-info "Invalid database argument map"
                    {::anomaly/category ::anomaly/incorrect
                     :explain (m/explain schema arg-map)
                     :value arg-map})))
  arg-map)

(defn query [conn arg-map]
  (-query conn (validate! schema/connection-query-arg-map arg-map)))

(defn transact! [conn arg-map]
  (-transact! conn (validate! schema/connection-transact-arg-map arg-map)))
