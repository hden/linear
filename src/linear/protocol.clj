(ns linear.protocol
  (:require
   [linear.spec :refer [spec-for]]))

(defprotocol Controllable
  (-ready? [controllable]))

(defn controllable? [x]
  (satisfies? Controllable x))

(defmethod spec-for ::controllable [_]
  [:fn controllable?])
