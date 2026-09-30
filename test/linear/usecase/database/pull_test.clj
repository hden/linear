(ns linear.usecase.database.pull-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres]
   [linear.adapter.slatedb.store]
   [linear.test :refer [run]]
   [linear.test-data.postgres :as postgres-data]
   [linear.test-data.slatedb :as slatedb-data]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]))

(deftest ^:integration pull-traverses-postgres-vault-decryption-and-slatedb
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store]})]
    (let [datasource  (:duct.database.sql/hikaricp system)
          store       (:linear.adapter.slatedb.store/store system)
          {:keys [database-id master-key keychain]}
          (postgres-data/create-database! {:datasource datasource})
          context     {::core/database datasource
                       ::core/master-key master-key
                       ::core/revision-store store}
          page        (byte-array [1 2 3 4])]
      (slatedb-data/store-root! {:store store :database-id database-id :keychain keychain :pages {1 page}})
      (let [result (database/pull context {:actor "actor-1" :database-id database-id})]
        (is (= "r-root" (:server-revision result)))
        (is (= 1 (:database-page-count result)))
        (is (= (seq page) (seq (get-in result [:pages 1]))))))))

(deftest ^:integration pull-fetches-pages-before-the-view-and-read-transaction-close
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]} (postgres-data/create-database! {:datasource datasource})
          read-connection (atom nil)
          transactable (reify transaction/Transactable
                         (-transact [_ f options]
                           (is (= {:read-only true} options))
                           (transaction/-transact datasource
                                                  (fn [connection]
                                                    (reset! read-connection connection)
                                                    (f connection))
                                                  options)))
          active? (atom false)
          fetched? (atom false)
          page (byte-array [1 2])
          snapshot (reify revisions/Snapshot
                     (-revision-id [_] "r-current")
                     (-size [_] 1)
                     (-fetch-pages-by-ids [_ {:keys [ids]}]
                       (is @active?)
                       (is (false? (.isClosed ^java.sql.Connection @read-connection)))
                       (is (= #{1} ids))
                       (reset! fetched? true)
                       {1 page}))
          view (reify revisions/ConsistentView
                 (-head [_] snapshot)
                 (-as-of [_ _] snapshot)
                 (-changes-since [_ _ _] #{}))
          reader (reify revisions/ConsistentReadable
                   (-read-consistently [_ f _]
                     (reset! active? true)
                     (try (f view) (finally (reset! active? false)))))
          result (database/pull {::core/database transactable
                                 ::core/master-key master-key
                                 ::core/revision-store reader}
                                {:actor "actor-1" :database-id database-id})]
      (is @fetched?)
      (is (false? @active?))
      (is (.isClosed ^java.sql.Connection @read-connection))
      (is (= {1 page} (:pages result))))))

(deftest ^:integration vault-recovery-preserves-stored-pages-and-in-flight-pull
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          store (:linear.adapter.slatedb.store/store system)
          {:keys [database-id vault-id master-key keychain]}
          (postgres-data/create-database! {:datasource datasource})
          ctx {::core/database datasource ::core/master-key master-key ::core/revision-store store}
          args {:actor "actor-1" :vault-id vault-id}
          token (#'vault/recovery-token ctx args)
          delete-fn #'vault/delete!
          restore-fn #'vault/restore!
          page (byte-array [10 20 30])
          reader (reify revisions/ConsistentReadable
                   (-read-consistently [_ f database]
                     (delete-fn ctx args)
                     (revisions/-read-consistently store f database)))
          pull-args {:actor "actor-1" :database-id database-id}]
      (slatedb-data/store-root! {:store store :database-id database-id
                                 :keychain keychain :pages {1 page}})
      (is (= (seq page)
             (seq (get-in (database/pull (assoc ctx ::core/revision-store reader) pull-args)
                          [:pages 1]))))
      (is (thrown? clojure.lang.ExceptionInfo (database/pull ctx pull-args)))
      (restore-fn ctx (assoc args :token token))
      (is (= (seq page) (seq (get-in (database/pull ctx pull-args) [:pages 1])))))))
