(ns linear.adapter.pagestore.core-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.core :as pagestore]))

(defn- validation-error [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- echo-database []
  (reify pagestore/Database
    (-size [_] 0)
    (-fetch-pages-by-ids [_ arg-map] arg-map)))

(deftest operations-reject-invalid-argument-maps
  (doseq [operation [#(pagestore/create-db! nil {})
                     #(pagestore/fetch-pages-by-ids (echo-database) {:ids "page"})]]
    (is (= ::anomaly/incorrect
           (-> (validation-error operation) ex-data ::anomaly/category)))))
