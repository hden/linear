(ns linear.usecase.core
  (:require
   [linear.adapter.postgres.datasource]
   [linear.adapter.sqlite.evaluator]
   [linear.protocol :as protocol]
   [linear.spec :refer [spec-for]]))

(defmethod spec-for ::context [_]
  [:map
   [::postgres-datasource :any]
   [::sqlite-evaluator [:and ::protocol/evaluator ::protocol/checkable]]])

(defn postgres-datasource
  {:malli/schema [:-> ::context
                      :any]}
  [{::keys [postgres-datasource]}]
  postgres-datasource)

(defn sqlite-evaluator
  {:malli/schema [:-> ::context
                      :any]}
  [{::keys [sqlite-evaluator]}]
  sqlite-evaluator)

(defn checkables
  {:malli/schema [:-> ::context
                      [:sequential ::protocol/checkable]]}
  [context]
  (into [] (filter protocol/checkable?) (vals context)))
