(ns linear.adapter.postgres.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres.core :as core]
   [linear.test :refer [run]]))

(deftest query
  (with-system [sys (run {:keys [:duct.database/sql]})]
    (let [datasource (:duct.database.sql/hikaricp sys)]
      (testing "test harness"
        (is (core/datasource? datasource)))

      (testing "schema migrations"
        (is (<= 1
                (core/query datasource {:statement {:select [[[:count :*] :count]]
                                                    :from :ragtime-migrations}
                                        :parse-fn  #(get-in % [0 :count])})))))))
