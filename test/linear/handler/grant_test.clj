(ns linear.handler.grant-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres]
   [linear.handler.grant :as handler]
   [linear.test :refer [run]]
   [linear.test-data.postgres :as postgres]
   [linear.usecase.core :as core]))

(defn- request [actor vault-id]
  {:identity {:sub actor}
   :path-params {:id vault-id}})

(deftest ^:integration grant-handlers-enforce-manage-and-translate-the-wire-format
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [database (:duct.database.sql/hikaricp system)
          context  {::core/database database}
          owner    (str "auth0|owner-" (random-uuid))
          reader   "auth0|reader/email@example.com"
          vault-id (:vault-id (postgres/create-database! {:datasource database
                                                          :actor owner}))
          owner-request (request owner vault-id)
          reader-request (request reader vault-id)]
      (is (= 400 (:status ((handler/set-grant context)
                           (assoc owner-request :body-params {:permission "admin"})))))
      (is (= 400 (:status ((handler/set-grant context)
                           (assoc owner-request :body-params 42)))))
      (is (= 204 (:status ((handler/set-grant context)
                           (assoc owner-request
                                  :body-params {:permission "pull"}
                                  :path-params {:id vault-id :subject reader})))))
      (is (= 403 (:status ((handler/list-grants context) reader-request))))
      (is (= {:status 200
              :body {:grants [{:subject owner :permission "manage"}
                              {:subject reader :permission "pull"}]}}
             ((handler/list-grants context) owner-request)))
      (is (= 204 (:status ((handler/revoke-grant context)
                           (assoc-in owner-request [:path-params :subject] reader)))))
      (is (= 204 (:status ((handler/revoke-grant context)
                           (assoc-in owner-request [:path-params :subject] reader))))))))
