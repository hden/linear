(ns linear.handler.turso.pull-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres]
   [linear.adapter.slatedb.store]
   [linear.handler.turso.pull :as pull]
   [linear.test :refer [run]]
   [linear.test-data.postgres :as postgres-data]
   [linear.test-data.slatedb :as slatedb-data]
   [linear.test-data.sqlite :as sqlite-data]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction]
   [ring.core.protocols :as ring])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)))

(deftest pull-handler-rejects-a-body-with-an-unsupported-type
  (let [response ((pull/handler {}) {:body "not-an-input-stream"})]
    (is (= 400 (:status response)))))

(deftest pull-handler-translates-backend-anomalies
  (doseq [[category status] [[:cognitect.anomalies/incorrect 400]
                             [:cognitect.anomalies/not-found 400]
                             [:cognitect.anomalies/unavailable 500]
                             [:cognitect.anomalies/fault 500]]]
    (let [database (reify transaction/Transactable
                     (-transact [_ _ _]
                       (throw (ex-info "backend failed"
                                       {:cognitect.anomalies/category category}))))
          response ((pull/handler {::core/database database})
                    {:body (byte-array 0) :path-params {:id "d-test"}})]
      (is (= status (:status response))))))

(deftest ^:integration pull-handler-accepts-byte-arrays-and-input-streams
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          store      (:linear.adapter.slatedb.store/store system)
          {:keys [database-id master-key keychain]}
          (postgres-data/create-database! {:datasource datasource})
          page       (byte-array [1 2 3 4])
          handler    (pull/handler {::core/database datasource
                                    ::core/master-key master-key
                                    ::core/revision-store store})]
      (slatedb-data/store-root! {:store store
                                 :database-id database-id
                                 :keychain keychain
                                 :pages {1 page}})
      (doseq [body [(byte-array 0)
                    (ByteArrayInputStream. (byte-array 0))]]
        (let [response (handler {:body body :path-params {:id database-id}})
              output   (ByteArrayOutputStream.)]
          (is (= 200 (:status response)))
          (is (= "application/octet-stream"
                 (get-in response [:headers "content-type"])))
          (ring/write-body-to-stream (:body response) nil output)
          (is (= [18 10 6 114 45 114 111 111 116 16 1]
                 (mapv #(bit-and (int %) 0xff)
                       (take 11 (.toByteArray output))))))))))
