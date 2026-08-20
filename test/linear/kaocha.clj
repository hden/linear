(ns linear.kaocha
  (:require
   [clojure.string :as str]
   [cognitect.anomalies :as anomalies]
   [linear.spec :refer [spec-for]]
   [malli.core :as m]
   [malli.dev.pretty :as pretty]
   [malli.instrument :as mi]
   [malli.registry :as mr]))

(defn target-namespaces []
  (into []
        (comp
          (map ns-name)
          (filter #(str/starts-with? (str %) "linear."))
          (remove #(str/ends-with? (str %) "-test")))
        (all-ns)))

(def pretty-report
  (pretty/reporter
    (pretty/-printer
      {:width 70
       :print-length 50
       :print-level 6})))

(defn- anomaly-reporter [type data]
  (pretty-report type data)

  (let [category
        (case type
          ::m/invalid-arity  :cognitect.anomalies/incorrect
          ::m/invalid-input  :cognitect.anomalies/incorrect
          ::m/invalid-output :cognitect.anomalies/fault
          ::anomalies/fault)]
    (throw
      (ex-info
        (str type)
        {::anomalies/category category
         ::anomalies/message  (str type)
         :malli/type type
         :malli/data data}))))

(defn instrument [test-plan]
  (let [namespaces (target-namespaces)
        namespace? (set namespaces)]
    (mi/collect! {:ns namespaces})
    (mi/instrument!
      {:report anomaly-reporter
       :filters
       [(fn [ns _name _data]
          (contains? namespace? ns))]})
    test-plan))

(def local-registry
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

(mr/set-default-registry! registry)
