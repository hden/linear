(ns linear.usecase.database.evaluator
  (:require
   [linear.spec :refer [spec-for]]
   [linear.usecase.database.revisions :as revisions]))

(defmethod spec-for ::sqlite-scalar [_]
  [:fn #(or (nil? %)
            (instance? Long %)
            (instance? Double %)
            (string? %)
            (bytes? %))])

(defmethod spec-for ::statement [_]
  [:map {:closed true}
   [:sql :string]
   [:parameters [:vector ::sqlite-scalar]]])

(defmethod spec-for ::push-command [_]
  [:map
   [:statements [:vector ::statement]]])

(defprotocol Evaluator
  (-evaluate [evaluator arg-map]))

(defprotocol Initializer
  (-initial-revision [initializer]))

(defn initial-revision
  {:malli/schema [:-> [:fn #(satisfies? Initializer %)] ::revisions/revision]}
  [initializer]
  (-initial-revision initializer))

(defn evaluator? [value]
  (satisfies? Evaluator value))

(defmethod spec-for ::evaluator [_]
  [:fn evaluator?])

(defn evaluate
  {:malli/schema [:->
                  ::evaluator
                  [:map
                   [:snapshot ::revisions/snapshot]
                   [:command ::push-command]]
                  ::revisions/revision]}
  [evaluator arg-map]
  (-evaluate evaluator arg-map))
