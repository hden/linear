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
   [:statements [:vector ::statement]]
   [:sync-progress {:optional true} ::sync-progress]])

(defmethod spec-for ::sync-progress [_]
  [:map
   [:client-id [:string {:min 1}]]
   [:generation [:int {:min 0}]]
   [:change-id [:int {:min 0}]]])

(defprotocol SyncProgressReadable
  (-sync-progress [reader arg-map]))

(defmethod spec-for ::sync-progress-readable [_]
  [:fn #(satisfies? SyncProgressReadable %)])

(defn sync-progress
  {:malli/schema [:-> ::sync-progress-readable
                  [:map [:snapshot ::revisions/snapshot] [:client-id [:string {:min 1}]]]
                  [:maybe ::sync-progress]]}
  [reader arg-map]
  (-sync-progress reader arg-map))

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
                  [:maybe ::revisions/revision]]}
  [evaluator arg-map]
  (-evaluate evaluator arg-map))
