(ns linear.adapter.pagestore.integration-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.pagestore.impl.core :as pagestore]
   [linear.adapter.pagestore.impl.slatedb.fixture :as slatedb]
   [linear.adapter.pagestore.impl.sqlite.driver :as driver]
   [linear.adapter.pagestore.impl.sqlite.fixture :as sqlite]
   [linear.adapter.pagestore.impl.sqlite.transactor :as transactor]))

(deftest sqlite-transactor-reads-a-slatedb-memory-snapshot
  (let [parent-id "r0"
        image     (sqlite/sqlite-image)
        runtime   (driver/install-vfs! {:library (sqlite/sqlite-library)
                                        :name    (str "linear-test-" (random-uuid))})]
    (try
      (slatedb/with-database
        {:head      {:revision-id parent-id}
         :revisions {parent-id {:id                  parent-id
                                :database-page-count (sqlite/page-count image)}}
         :pages     (sqlite/revision-pages parent-id image)}
        (fn [snapshot]
          (let [revision (pagestore/ingest
                           (transactor/transactor runtime)
                           {:database  snapshot
                            :operation (fn [execute-sql]
                                         (execute-sql "UPDATE t SET value='captured'"))})]
            (is (= parent-id (:parent revision)))
            (is (string? (:revision-id revision)))
            (is (seq (:pages revision))))))
      (finally
        (driver/close-vfs! runtime)))))
