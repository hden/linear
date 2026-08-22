(ns linear.usecase.healthcheck
  (:require
   [linear.adapter.postgres.controllable :as controllable]
   [linear.usecase.core :as core]))

(defn ready?
  {:malli/schema [:-> ::core/context
                      :boolean]}
  [context]
  (let [ctrl (core/controllable context)]
    (controllable/ready? ctrl)))
