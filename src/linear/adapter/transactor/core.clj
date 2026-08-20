(ns linear.adapter.transactor.core
  (:require
   [linear.spec :refer [spec-for]]))

(defprotocol Transactor
  (-ingest [transactor arg-map])
  (-vacuum [transactor arg-map]))

(defn transactor? [x]
  (satisfies? Transactor x))

(defmethod spec-for ::transactor [_]
  [:fn transactor?])

(defn ingest
  "extract diffs from applying changes
   VFS. no visible side-effects
   returns a revision"
  {:malli/schema [:-> ::transactor
                      [:map]
                      [:map]]}
  [transactor arg-map]
  (-ingest transactor arg-map))

(defn vacuum
  "extract diffs from applying incremental vacuum
   VFS. no visible side-effects
   returns a revision"
  {:malli/schema [:-> ::transactor
                      [:map]
                      [:map]]}
  [transactor arg-map]
  (-vacuum transactor arg-map))
