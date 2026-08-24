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

(defprotocol Evaluator
  (-eval [evaluator arg-map])
  (-vacuum [evaluator arg-map]))

(defn evaluator? [x]
  (satisfies? Evaluator x))

(defmethod spec-for ::evaluator [_]
  [:fn evaluator?])

(defn evaluate
  {:malli/schema [:->
                  ::evaluator
                  [:map
                   [:snapshot ::snapshot]
                   [:operation [:fn ifn?]]]
                  :map]}
  [evaluator arg-map]
  (-eval evaluator arg-map))

(defn vacuum
  {:malli/schema [:->
                  ::evaluator
                  [:map
                   [:snapshot ::snapshot]]
                  :map]}
  [evaluator arg-map]
  (-vacuum evaluator arg-map))

(defprotocol Snapshot
  (-revision-id [snapshot])
  (-size [snapshot])
  (-fetch-pages-by-ids [snapshot arg-map]))

(defn snapshot? [x]
  (satisfies? Snapshot x))

(defmethod spec-for ::snapshot [_]
  [:fn snapshot?])

(defn revision-id
  {:malli/schema [:->
                  ::snapshot
                  :any]}
  [snapshot]
  (-revision-id snapshot))
