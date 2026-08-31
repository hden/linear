(ns linear.usecase.core
  (:require
   [linear.adapter.postgres.datasource]
   [linear.adapter.slatedb.store]
   [linear.adapter.sqlite.evaluator]
   [linear.protocol :as protocol]
   [linear.spec :refer [spec-for]]
   [linear.usecase.database :as database]))

(defmethod spec-for ::context [_]
  [:map
   [::postgres-datasource :any]
   [::database-evaluator [:and ::database/evaluator ::protocol/checkable]]
   [::snapshot-reader ::database/snapshot-reader]
   [::revision-writer ::database/revision-writer]])

(defn vault-datasource
  {:malli/schema [:-> ::context
                      :any]}
  [{::keys [postgres-datasource]}]
  postgres-datasource)

(defn database-evaluator
  {:malli/schema [:-> ::context
                      :any]}
  [{::keys [database-evaluator]}]
  database-evaluator)

(defn snapshot-reader
  {:malli/schema [:-> ::context
                      ::database/snapshot-reader]}
  [{::keys [snapshot-reader]}]
  snapshot-reader)

(defn revision-writer
  {:malli/schema [:-> ::context
                      ::database/revision-writer]}
  [{::keys [revision-writer]}]
  revision-writer)

(defn checkables
  {:malli/schema [:-> ::context
                      [:sequential ::protocol/checkable]]}
  [context]
  (into [] (filter protocol/checkable?) (vals context)))
