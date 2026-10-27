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
          {:keys [database-id key-protection keychain]}
          (postgres-data/create-database! {:datasource datasource})
          context     {::core/database datasource
                       ::core/key-service key-protection
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
          {:keys [database-id key-protection]} (postgres-data/create-database! {:datasource datasource})
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
                                 ::core/key-service key-protection
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
          {:keys [database-id vault-id key-protection keychain]}
          (postgres-data/create-database! {:datasource datasource})
          ctx {::core/database datasource ::core/key-service key-protection ::core/revision-store store}
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

(deftest ^:integration long-poll-caps-waiting-and-closes-read-scopes-before-awaiting
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          store (:linear.adapter.slatedb.store/store system)
          {:keys [database-id key-protection keychain]}
          (postgres-data/create-database! {:datasource datasource})
          connection (atom nil)
          viewing? (atom false)
          waits (atom [])
          transactable (reify transaction/Transactable
                         (-transact [_ f options]
                           (transaction/-transact datasource
                                                  (fn [tx] (reset! connection tx) (f tx)) options)))
          reader (reify
                   revisions/ConsistentReadable
                   (-read-consistently [_ f database]
                     (revisions/-read-consistently store
                       (fn [view]
                         (reset! viewing? true)
                         (try (f view) (finally (reset! viewing? false))))
                       database))
                   revisions/RevisionAwaitable
                   (-await-revision [_ args]
                     (is (false? @viewing?))
                     (is (.isClosed ^java.sql.Connection @connection))
                     (swap! waits conj (select-keys args [:revision-id :timeout-ms]))
                     false))
          context {::core/database transactable ::core/key-service key-protection
                   ::core/revision-store reader ::core/max-long-poll-timeout-ms 40}
          args {:actor "actor-1" :database-id database-id}]
      (slatedb-data/store-root! {:store store :database-id database-id :keychain keychain :pages {}})
      (doseq [options [{:long-poll-timeout-ms 5000}
                       {:client-revision "r-root" :long-poll-timeout-ms 0}
                       {:client-revision "r-root" :server-revision "r-root" :long-poll-timeout-ms 5000}]]
        (is (= "r-root" (:server-revision (database/pull context (merge args options))))))
      (is (empty? @waits))
      (doseq [timeout [10 5000 0xffffffff]]
        (is (= {} (:pages (database/pull context (assoc args :client-revision "r-root"
                                                   :long-poll-timeout-ms timeout))))))
      (is (= [{:revision-id "r-root" :timeout-ms 10}
              {:revision-id "r-root" :timeout-ms 40}
              {:revision-id "r-root" :timeout-ms 40}] @waits))
      (revisions/publish-next! store
        {:database {:id database-id :vault {:keychain keychain}}
         :revision {:revision-id "r-next" :parent "r-root" :database-page-count 1 :pages {1 (byte-array [7])}}})
      (let [result (database/pull context (assoc args :client-revision "r-root" :long-poll-timeout-ms 5000))]
        (is (= "r-next" (:server-revision result)))
        (is (= {1 '(7)} (update-vals (:pages result) seq)))
        (is (= 3 (count @waits)))))))

(deftest ^:integration long-poll-rechecks-database-access-after-waiting
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          store (:linear.adapter.slatedb.store/store system)
          {:keys [database-id key-protection keychain]}
          (postgres-data/create-database! {:datasource datasource})
          context {::core/database datasource ::core/key-service key-protection ::core/revision-store store}
          args {:actor "actor-1" :database-id database-id}
          reader (reify
                   revisions/ConsistentReadable
                   (-read-consistently [_ f database] (revisions/-read-consistently store f database))
                   revisions/RevisionAwaitable
                   (-await-revision [_ _]
                     (database/close! context args)
                     false))]
      (slatedb-data/store-root! {:store store :database-id database-id :keychain keychain :pages {}})
      (is (= ::database/database-not-found
             (:reason (linear.test/catch-ex-data
                        #(database/pull (assoc context ::core/revision-store reader)
                                        (assoc args :client-revision "r-root" :long-poll-timeout-ms 100)))))))))
