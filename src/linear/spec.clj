(ns linear.spec
  (:require
   [malli.core :as m]
   [malli.registry :as mr]))

(defmulti spec-for identity)

(defmethod spec-for :default [_]
  nil)

(def ^:private local-registry
  (reify mr/Registry
    (-schema [_ key]
      (spec-for key))
    (-schemas [_]
      (into {}
            (comp
              (filter (fn [[key & _]]
                        (not= key :default)))
              (map (fn [[key f]]
                     [key (f key)])))
            (methods spec-for)))))

(def registry
  (mr/composite-registry
    (m/default-schemas)
    local-registry))

(defn valid?
  [schema value]
  (m/validate schema value {:registry registry}))
