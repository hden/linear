(ns linear.handler.database-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.handler.database :as database]
   [linear.handler.grant :as grant]
   [linear.handler.vault :as vault]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction]))

(deftest malformed-create-and-update-inputs-are-rejected-before-storage
  (let [create (database/create {})
        update (database/update-attributes {})
        request {:identity {:sub "actor"} :path-params {:id "v-test"}
                 :headers {"idempotency-key" "create-test"}}]
    (doseq [body [nil [] {} {:display-name ""} {:display-name 1}
                  {:display-name "Valid" :extra true}
                  {:display-name "Valid" :source {}}
                  {:display-name "Valid" :source {:database-id "invalid"}}
                  {:display-name "Valid" :source {:database-id "d-valid" :revision-id nil}}
                  {:display-name "Valid" :source {:database-id "d-valid" :extra true}}]]
      (is (= 400 (:status (create (assoc request :body-params body)))) (pr-str body)))
    (doseq [key [nil ""]]
      (let [response (create (assoc request :headers {"idempotency-key" key}
                               :body-params {:display-name "Valid"}))]
        (is (= 400 (:status response)) (pr-str key))))
    (doseq [body [nil {} {:display-name ""} {:display-name false} {:display-name "Valid" :source {}}]]
      (is (= 400 (:status (update (assoc request :body-params body)))) (pr-str body)))))

(deftest malformed-state-filter-is-rejected-before-storage
  (doseq [state ["" "deleted" "ACTIVE" ["active" "closed"]]]
    (is (= 400 (:status ((database/list-by-vault {}) {:query-params {"state" state}}))) (pr-str state))))

(deftest management-handlers-share-anomaly-http-policy
  (doseq [[category status message]
          [[::anomaly/incorrect 400 "domain detail"]
           [::anomaly/forbidden 403 "Forbidden"]
           [::anomaly/not-found 404 "domain detail"]
           [::anomaly/conflict 409 "domain detail"]
           [::anomaly/unavailable 500 "domain detail"]
           [::anomaly/fault 500 "domain detail"]]
          handler [database/get-by-id vault/get-state grant/list-grants]]
    (let [error (ex-info "operation failed" {::anomaly/category category
                                             ::anomaly/message "domain detail"})
          context {::core/database (reify transaction/Transactable
                                     (-transact [_ _ _] (throw error)))}
          response ((handler context) {:identity {:sub "actor"}
                                       :path-params {:id "d-test"}})]
      (is (= {:status status :body {:error message}} response)))))

(deftest management-handlers-read-anomalies-through-exception-wrappers
  (doseq [handler [database/get-by-id vault/get-state grant/list-grants]]
    (let [error (ex-info "execution wrapper" {}
                  (ex-info "domain error" {::anomaly/category ::anomaly/conflict
                                           ::anomaly/message "resource conflict"}))
          context {::core/database (reify transaction/Transactable
                                     (-transact [_ _ _] (throw error)))}]
      (is (= {:status 409 :body {:error "resource conflict"}}
             ((handler context) {:identity {:sub "actor"}
                                 :path-params {:id "d-test"}}))))))
