(ns linear.adapter.transactor.core
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.transactor.schema :as schema]
   [malli.core :as m]))

(defprotocol Transactor
  (-ingest [transactor arg-map])
  (-vacuum [transactor arg-map]))

(defn- validate! [schema arg-map]
  (when-not (m/validate schema arg-map)
    (throw (ex-info "Invalid object store argument map"
                    {::anomaly/category ::anomaly/incorrect
                     :explain (m/explain schema arg-map)
                     :value arg-map})))
  arg-map)

(defn ingest
  "extract diffs from applying changes
   VFS. no visible side-effects
   returns a revision"
  [transactor arg-map]
  (-ingest transactor (validate! schema/transactor-ingest-arg-map arg-map)))

(defn vacuum
  "extract diffs from applying incremental vacuum
   VFS. no visible side-effects
   returns a revision"
  [transactor arg-map]
  (-vacuum transactor (validate! schema/transactor-vacuum-arg-map arg-map)))
