(ns linear.handler.database-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.database :as database]))

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
