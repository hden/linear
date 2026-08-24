(ns linear.usecase.healthcheck
  (:require
   [linear.protocol :as protocol]
   [linear.usecase.core :as core]))

(defn- every-check? [context check]
  (try
    (let [checkables (core/checkables context)]
      (and (seq checkables)
           (every? check checkables)))
    (catch Exception _
      false)))

(defn ready?
  {:malli/schema [:-> ::core/context
                      :boolean]}
  [context]
  (every-check? context protocol/ready?))

(defn ok?
  {:malli/schema [:-> ::core/context
                      :boolean]}
  [context]
  (every-check? context protocol/ok?))
