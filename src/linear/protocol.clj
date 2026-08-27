(ns linear.protocol
  (:require
   [linear.spec :refer [spec-for]]))

(defprotocol Checkable
  (-ready? [checkable])
  (-ok? [checkable]))

(defn checkable? [x]
  (satisfies? Checkable x))

(defmethod spec-for ::checkable [_]
  [:fn checkable?])

(defn ready?
  {:malli/schema [:->
                  ::checkable
                  :boolean]}
  [checkable]
  (-ready? checkable))

(defn ok?
  {:malli/schema [:->
                  ::checkable
                  :boolean]}
  [checkable]
  (-ok? checkable))
