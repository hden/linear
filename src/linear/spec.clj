(ns linear.spec)

(defmulti spec-for identity)

(defmethod spec-for :default [_]
  nil)
