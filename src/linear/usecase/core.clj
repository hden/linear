(ns linear.usecase.core
  (:require
   [linear.spec :refer [spec-for]]))

(defmethod spec-for ::context [_]
  [:map
   [::postgres-datasource :any]])

(defn controllable
  {:malli/schema [:-> ::context
                      :any]}
  [{::keys [postgres-datasource]}]
  postgres-datasource)
