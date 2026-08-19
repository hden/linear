(ns linear.adapter.pagestore.core
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.schema :as schema]
   [malli.core :as m]))

(defprotocol Connection
  (-create-db! [conn arg-map])
  (-execute! [conn db f arg-map]))

(defprotocol Database
  (-size [db])
  (-fetch-pages-by-ids [db arg-map]))

(defn- validate! [schema arg-map]
  (when-not (m/validate schema arg-map)
    (throw (ex-info "Invalid object store argument map"
                    {::anomaly/category ::anomaly/incorrect
                     :explain (m/explain schema arg-map)
                     :value arg-map})))
  arg-map)

(defn create-db! [conn arg-map]
  (-create-db! conn (validate! schema/database-create-arg-map arg-map)))

(defn execute!
  ([conn db f]
   (execute! conn db f {}))
  ([conn db f arg-map]
   (-execute! conn db f arg-map)))

(defn size [db]
  (-size db))

(defn fetch-pages-by-ids [db arg-map]
  (-fetch-pages-by-ids db (validate! schema/database-fetch-pages-by-ids-arg-map arg-map)))
