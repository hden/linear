(ns linear.adapter.database.core-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.database.core :as database]))

(defn- echo-connection []
  (reify database/Connection
    (-query [_ arg-map] arg-map)
    (-transact! [_ arg-map] arg-map)))

(defn- validation-error [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo error
      error)))

(deftest query-requires-a-statement-map
  (doseq [arg-map [{} {:statement []}]]
    (is (= ::anomaly/incorrect
           (-> (validation-error #(database/query (echo-connection) arg-map))
               ex-data
               ::anomaly/category)))))

(deftest query-and-transact-accept-supported-argument-maps
  (let [connection (echo-connection)]
    (is (= {:statement {}}
           (database/query connection {:statement {}})))
    (doseq [statements [[{:statement {}}]
                        (list {:statement {}})
                        #{{:statement {}}}]]
      (is (= {:statements statements}
             (database/transact! connection {:statements statements}))))))
