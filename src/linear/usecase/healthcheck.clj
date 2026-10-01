(ns linear.usecase.healthcheck
  (:require
   [linear.usecase.core :as core]))

(defprotocol Checkable
  (-ready? [checkable])
  (-ok? [checkable]))

(defn- every-check? [context predicate]
  (try
    (let [checkables (core/checkables context)]
      (and (seq checkables)
           (every? predicate checkables)))
    (catch Exception _
      false)))

(defn ready?
  {:malli/schema [:-> [:map-of :keyword :any] :boolean]}
  [context]
  (every-check? context #(-ready? %)))

(defn ok?
  {:malli/schema [:-> [:map-of :keyword :any] :boolean]}
  [context]
  (every-check? context #(-ok? %)))
