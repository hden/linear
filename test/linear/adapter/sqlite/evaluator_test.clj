(ns linear.adapter.sqlite.evaluator-test
  (:require
   [clojure.test :refer [deftest is]]
   [integrant.core :as integrant]
   [linear.adapter.sqlite.evaluator]
   [linear.test-data.sqlite :as sqlite-data]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.revisions :as revisions]))

(defrecord FailingSnapshot [snapshot fetch-count failure]
  revisions/Snapshot
  (-revision-id [_]
    (revisions/revision-id snapshot))
  (-size [_]
    (revisions/size snapshot))
  (-fetch-pages-by-ids [_ arg-map]
    (if (= 1 (swap! fetch-count inc))
      (revisions/fetch-pages-by-ids snapshot arg-map)
      (throw failure))))

(defn- caused-by? [error cause]
  (loop [current error]
    (cond
      (nil? current) false
      (identical? current cause) true
      :else (recur (ex-cause current)))))

(deftest ^:integration evaluates-sql-through-the-native-evaluator
  (Class/forName "org.sqlite.JDBC")
  (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {})]
    (try
      (let [result (evaluator/evaluate
                     evaluator
                     {:snapshot (sqlite-data/snapshot {:image (sqlite-data/sqlite-image)})
                      :command {:statements
                                [{:sql "UPDATE t SET value = ? WHERE id = ?"
                                  :parameters ["through-evaluator" 1]}]}})]
        (is (= "r-0" (:parent result)))
        (is (pos? (:database-page-count result)))
        (is (seq (:pages result))))
      (finally
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)))))

(deftest ^:integration computes-independent-revisions-from-the-same-snapshot
  (Class/forName "org.sqlite.JDBC")
  (let [base (sqlite-data/snapshot {:image (sqlite-data/sqlite-image)})
        evaluator  (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {})]
    (try
      (let [evaluate-async (fn [value]
                             (future
                               (evaluator/evaluate
                                 evaluator
                                 {:snapshot base
                                  :command {:statements
                                            [{:sql "UPDATE t SET value = ? WHERE id = 1"
                                              :parameters [value]}]}})))
            left           (evaluate-async "left")
            right          (evaluate-async "right")]
        (is (= "r-0" (:parent @left)))
        (is (= "r-0" (:parent @right)))
        (is (not= (:revision-id @left) (:revision-id @right))))
      (finally
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)))))

(deftest ^:integration binds-all-domain-scalar-types
  (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {})]
    (try
      (let [result (evaluator/evaluate
                     evaluator
                     {:snapshot (sqlite-data/snapshot {:image (sqlite-data/sqlite-image)})
                      :command
                      {:statements
                       [{:sql "UPDATE t SET value = ? WHERE id = ?"
                         :parameters ["bound" 1]}
                        {:sql (str "SELECT CASE WHEN ? IS NULL "
                                "AND typeof(?) = 'integer' AND ? = 42 "
                                "AND typeof(?) = 'real' AND ? = 1.5 "
                                "AND typeof(?) = 'text' AND ? = 'text' "
                                "AND typeof(?) = 'blob' AND ? = x'0102' "
                                "THEN 1 ELSE abs(-9223372036854775808) END")
                         :parameters [nil
                                      42 42
                                      1.5 1.5
                                      "text" "text"
                                      (byte-array [1 2]) (byte-array [1 2])]}]}})]
        (is (= "r-0" (:parent result))))
      (finally
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)))))

(deftest ^:integration rejects-multiple-statements-and-transaction-control
  (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {})
        base      (sqlite-data/snapshot {:image (sqlite-data/sqlite-image)})]
    (try
      (doseq [sql ["UPDATE t SET value = 'one'; UPDATE t SET value = 'two'"
                   "COMMIT"]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (evaluator/evaluate evaluator
                       {:snapshot base
                        :command {:statements [{:sql sql
                                                :parameters []}]}}))))
      (finally
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)))))

(deftest ^:integration evaluator-recovers-after-a-statement-failure
  (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {})
        base      (sqlite-data/snapshot {:image (sqlite-data/sqlite-image)})]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (evaluator/evaluate evaluator
                     {:snapshot base
                      :command {:statements
                                [{:sql "UPDATE missing_table SET value = 1"
                                  :parameters []}]}})))
      (let [result (evaluator/evaluate evaluator
                     {:snapshot base
                      :command {:statements
                                [{:sql "UPDATE t SET value = ? WHERE id = 1"
                                  :parameters ["after-failure"]}]}})]
        (is (= "r-0" (:parent result)))
        (is (seq (:pages result))))
      (finally
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)))))

(deftest ^:integration evaluator-recovers-after-a-snapshot-callback-failure
  (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator {})
        base      (sqlite-data/snapshot {:image (sqlite-data/sqlite-image)})
        cause     (ex-info "snapshot read failed" {:reason ::snapshot-read-failed})
        failing   (->FailingSnapshot base (atom 0) cause)]
    (try
      (let [failure (try
                      (evaluator/evaluate evaluator
                        {:snapshot failing
                         :command {:statements []}})
                      nil
                      (catch Exception error
                        error))]
        (is (caused-by? failure cause)))
      (let [result (evaluator/evaluate evaluator
                     {:snapshot base
                      :command {:statements
                                [{:sql "UPDATE t SET value = ? WHERE id = 1"
                                  :parameters ["after-callback-failure"]}]}})]
        (is (= "r-0" (:parent result))))
      (finally
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)))))
