(ns linear.adapter.postgres.database-integration-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.test :refer [run]]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.transaction :as transaction]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel]))

(defn- seed-database! [datasource database-id vault-id ciphertext tx-id attributes-id]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx ["INSERT INTO transactions (id) VALUES (?)" tx-id])
    (jdbc/execute! tx
                   ["INSERT INTO vaults (id, owner, ciphertext, encrypted_by, created_by) VALUES (?, ?, ?, ?, ?)"
                    vault-id "owner-1" ciphertext "dev-ephemeral" tx-id])
    (jdbc/execute! tx
                   ["INSERT INTO databases (id, encrypted_by, current_attributes) VALUES (?, ?, ?)"
                    database-id vault-id attributes-id])
    (jdbc/execute! tx
                   ["INSERT INTO database_attributes (id, database_id, display_name, created_by) VALUES (?, ?, ?, ?)"
                    attributes-id database-id "Primary" tx-id])))

(defn- seed-database-tombstone! [datasource database-id tx-id]
  (jdbc/with-transaction [tx datasource]
    (jdbc/execute! tx
                   ["UPDATE databases SET current_attributes = NULL WHERE id = ?"
                    database-id])
    (jdbc/execute! tx
                   ["INSERT INTO database_tombstones (id, database_id, created_by) VALUES (?, ?, ?)"
                    (str "t-" (random-uuid)) database-id tx-id])))

(defn- seed-page! [store database-id keychain page]
  (let [revision     {:revision-id "r-root"
                      :parent nil
                      :database-page-count 1
                      :pages {1 page}}
        revision-key (key/revision "r-root")
        page-key     (key/page 1)]
    (with-open [database    (connection/database store database-id)
                transaction (connection/writable-transaction database)]
      (ffi/await
        (ffi/write-values
          transaction
          [[revision-key (codec/encode-revision keychain revision-key revision)]
           [page-key (codec/encode-page keychain page-key page)]
           [(key/head) (codec/encode-head {:revision-id "r-root"})]]))
      (ffi/await (ffi/commit-transaction transaction)))))

(defn- resolved-database-fixture! [datasource]
  (let [master-key    (crypto/keychain "dev-ephemeral" (tempel/keychain))
        keychain      (crypto/new-keychain)
        suffix        (random-uuid)
        vault-id      (str "v-" suffix)
        database-id   (str "d-" suffix)
        tx-id         (str "tx-" suffix)
        attributes-id (str "a-" suffix)]
    (seed-database! datasource
                    database-id
                    vault-id
                    (keychain/encrypt master-key keychain
                                      {:associated-data (.getBytes ^String vault-id "UTF-8")})
                    tx-id
                    attributes-id)
    {:database-id database-id
     :keychain keychain
     :master-key master-key}))

(defn- snapshot [revision-id]
  (reify revisions/Snapshot
    (-revision-id [_] revision-id)
    (-size [_] 1)
    (-fetch-pages-by-ids [_ _] {})))

(defn- view [events revision-id]
  (reify revisions/ConsistentView
    (-head [_]
      (swap! events conj :head)
      (snapshot revision-id))
    (-as-of [_ _]
      (snapshot revision-id))
    (-changes-since [_ _ _]
      #{})))

(defn- application-context [datasource master-key store evaluator]
  {::core/database datasource
   ::core/master-key master-key
   ::core/revision-store store
   ::core/evaluator evaluator})

(deftest resolve-by-id-treats-missing-and-tombstoned-databases-as-not-found
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (resolved-database-fixture! datasource)
          tx-id      (subs database-id 2)
          missing-id (str "d-" (random-uuid))
          resolve-error
          (fn [id]
            (try
              (jdbc/with-transaction [tx datasource {:read-only true}]
                (database/resolve-by-id master-key tx id))
              nil
              (catch clojure.lang.ExceptionInfo error
                error)))]
      (seed-database-tombstone! datasource database-id (str "tx-" tx-id))
      (doseq [id [missing-id database-id]]
        (let [error (resolve-error id)]
          (is (= ::anomaly/not-found (-> error ex-data ::anomaly/category)))
          (is (= ::database/database-not-found (-> error ex-data :reason)))
          (is (= id (-> error ex-data :database-id))))))))

(deftest pull-traverses-postgres-vault-decryption-and-slatedb
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          store      (:linear.adapter.slatedb.store/store system)
          master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))
          keychain   (crypto/new-keychain)
          context    {::core/database datasource
                      ::core/master-key master-key
                      ::core/revision-store store}
          suffix     (random-uuid)
          vault-id   (str "v-" suffix)
          database-id (str "d-" suffix)
          tx-id      (str "tx-" suffix)
          attributes-id (str "a-" suffix)
          page       (byte-array [1 2 3 4])]
      (seed-database! datasource
                      database-id
                      vault-id
                      (keychain/encrypt master-key keychain
                                        {:associated-data (.getBytes ^String vault-id "UTF-8")})
                      tx-id
                      attributes-id)
      (seed-page! store database-id keychain page)
      (let [result (database/pull context database-id {})]
        (is (= "r-root" (:server-revision result)))
        (is (= 1 (:database-page-count result)))
        (is (= (seq page) (seq (get-in result [:pages 1]))))))))

(deftest push-uses-the-database-resolved-from-the-application-context
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource  (:duct.database.sql/hikaricp system)
          master-key (crypto/keychain "dev-ephemeral" (tempel/keychain))
          keychain   (crypto/new-keychain)
          suffix     (random-uuid)
          vault-id   (str "v-" suffix)
          database-id (str "d-" suffix)
          tx-id      (str "tx-" suffix)
          attributes-id (str "a-" suffix)
          read-connection (atom nil)
          transactable (reify transaction/Transactable
                         (-transact [_ f options]
                           (is (= {:read-only true} options))
                           (transaction/-transact
                             datasource
                             (fn [connection]
                               (reset! read-connection connection)
                               (f connection))
                             options)))
          events     (atom [])
          snapshot   (reify revisions/Snapshot
                       (-revision-id [_] "r-current")
                       (-size [_] 1)
                       (-fetch-pages-by-ids [_ _] {}))
          view       (reify revisions/ConsistentView
                       (-head [_]
                         (swap! events conj :head)
                         snapshot)
                       (-as-of [_ _] snapshot)
                       (-changes-since [_ _ _] #{}))
          store      (reify
                       revisions/ConsistentReadable
                       (-read-consistently [_ f database]
                         (swap! events conj [:view-open database])
                         (try
                           (f view)
                           (finally
                             (swap! events conj :view-close))))
                       revisions/RevisionWritable
                       (-publish-next! [_ revision database]
                         (is (.isClosed ^java.sql.Connection @read-connection))
                         (swap! events conj [:publish revision database])
                         revision))
          evaluator  (reify evaluator/Evaluator
                       (-evaluate [_ {:keys [snapshot command]}]
                         (is (false? (.isClosed ^java.sql.Connection @read-connection)))
                         (let [parent (revisions/revision-id snapshot)]
                           (swap! events conj [:evaluate parent command])
                           {:revision-id "r-next"
                            :parent parent
                            :database-page-count 1
                            :pages {}})))
          context    {::core/database transactable
                      ::core/master-key master-key
                      ::core/revision-store store
                      ::core/evaluator evaluator}
          command    {:statements []}]
      (seed-database! datasource
                      database-id
                      vault-id
                      (keychain/encrypt master-key keychain
                                        {:associated-data (.getBytes ^String vault-id "UTF-8")})
                      tx-id
                      attributes-id)
      (let [revision (database/push! context database-id command)]
        (is (= "r-next" (:revision-id revision)))
        (let [[[_ database-at-read]
               _
               _
               _
               [_ _ database-at-publish]] @events]
          (is (= [:view-open
                  :head
                  :evaluate
                  :view-close
                  :publish]
                 (mapv #(if (vector? %) (first %) %) @events)))
          (is (= (:id database-at-read) (:id database-at-publish)))
          (is (identical? (get-in database-at-read [:vault :keychain])
                          (get-in database-at-publish [:vault :keychain])))
          (is (= {:id database-id
                  :display-name "Primary"
                  :vault {:id vault-id
                          :owner "owner-1"
                          :created (get-in database-at-read [:vault :created])
                          :keychain keychain}}
                 (select-keys database-at-read [:id :display-name :vault])))
          (is (inst? (get-in database-at-read [:vault :created])))
          (is (identical? view (model/consistent-view database-at-publish)))
          (is (identical? evaluator (model/evaluator database-at-publish)))
          (is (identical? store (::model/revision-writable database-at-publish))))))))

(deftest push-retries-evaluation-after-a-revision-conflict
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (resolved-database-fixture! datasource)
          resolved-names (atom [])
          evaluated    (atom [])
          revision-ids (atom ["r-first" "r-second"])
          publishes    (atom 0)
          store        (reify
                         revisions/ConsistentReadable
                         (-read-consistently [_ f database]
                           (swap! resolved-names conj (:display-name database))
                           (let [revision-id (first @revision-ids)]
                             (swap! revision-ids rest)
                             (f (view (atom []) revision-id))))
                         revisions/RevisionWritable
                         (-publish-next! [_ revision _]
                           (if (= 1 (swap! publishes inc))
                             (do
                               (jdbc/execute! datasource
                                              ["UPDATE database_attributes SET display_name = ? WHERE database_id = ?"
                                               "Renamed" database-id])
                               (throw (ex-info "Revision conflict"
                                               {::anomaly/category ::anomaly/conflict
                                                :reason ::revisions/revision-conflict})))
                             revision)))
          evaluator    (reify evaluator/Evaluator
                         (-evaluate [_ {:keys [snapshot]}]
                           (let [parent (revisions/revision-id snapshot)]
                             (swap! evaluated conj parent)
                             {:revision-id (str parent "-next")
                              :parent parent
                              :database-page-count 1
                              :pages {}})))
          result       (database/push!
                         (application-context datasource
                                              master-key
                                              store
                                              evaluator)
                         database-id
                         {:statements []})]
      (is (= "r-second-next" (:revision-id result)))
      (is (= ["r-first" "r-second"] @evaluated))
      (is (= ["Primary" "Renamed"] @resolved-names))
      (is (= 2 @publishes)))))

(deftest push-does-not-publish-a-revision-with-a-different-parent
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (resolved-database-fixture! datasource)
          published? (atom false)
          store      (reify
                       revisions/ConsistentReadable
                       (-read-consistently [_ f _]
                         (f (view (atom []) "r-current")))
                       revisions/RevisionWritable
                       (-publish-next! [_ revision _]
                         (reset! published? true)
                         revision))
          evaluator  (reify evaluator/Evaluator
                       (-evaluate [_ _]
                         {:revision-id "r-next"
                          :parent "r-stale"
                          :database-page-count 1
                          :pages {}}))
          error      (try
                       (database/push!
                         (application-context datasource
                                              master-key
                                              store
                                              evaluator)
                         database-id
                         {:statements []})
                       nil
                       (catch clojure.lang.ExceptionInfo failure
                         failure))]
      (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
      (is (= ::database/invalid-revision-parent (-> error ex-data :reason)))
      (is (false? @published?)))))

(deftest push-does-not-retry-a-non-conflict-failure
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (resolved-database-fixture! datasource)
          reads     (atom 0)
          failure   (ex-info "Unavailable" {::anomaly/category ::anomaly/unavailable})
          store     (reify
                      revisions/ConsistentReadable
                      (-read-consistently [_ _ _]
                        (swap! reads inc)
                        (throw failure))
                      revisions/RevisionWritable
                      (-publish-next! [_ _ _]
                        (throw (IllegalStateException. "must not publish"))))
          evaluator (reify evaluator/Evaluator
                      (-evaluate [_ _]
                        (throw (IllegalStateException. "must not evaluate"))))
          error     (try
                      (database/push!
                        (application-context datasource
                                             master-key
                                             store
                                             evaluator)
                        database-id
                        {:statements []})
                      nil
                      (catch clojure.lang.ExceptionInfo caught
                        caught))]
      (is (identical? failure error))
      (is (= 1 @reads)))))

(deftest push-translates-an-exhausted-revision-conflict
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (resolved-database-fixture! datasource)
          publishes (atom 0)
          store     (reify
                      revisions/ConsistentReadable
                      (-read-consistently [_ f _]
                        (f (view (atom []) "r-current")))
                      revisions/RevisionWritable
                      (-publish-next! [_ _ _]
                        (swap! publishes inc)
                        (throw (ex-info "Revision conflict"
                                        {::anomaly/category ::anomaly/conflict
                                         :reason ::revisions/revision-conflict}))))
          evaluator (reify evaluator/Evaluator
                      (-evaluate [_ _]
                        {:revision-id "r-next"
                         :parent "r-current"
                         :database-page-count 1
                         :pages {}}))
          error     (try
                      (database/push!
                        (application-context datasource
                                             master-key
                                             store
                                             evaluator)
                        database-id
                        {:statements []})
                      nil
                      (catch clojure.lang.ExceptionInfo caught
                        caught))]
      (is (= ::anomaly/conflict (-> error ex-data ::anomaly/category)))
      (is (= ::database/push-conflict (-> error ex-data :reason)))
      (is (= 3 (-> error ex-data :attempts)))
      (is (= 3 @publishes)))))

(deftest pull-fetches-pages-before-the-view-and-read-transaction-close
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]} (resolved-database-fixture! datasource)
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
                     (try
                       (f view)
                       (finally (reset! active? false)))))
          result (database/pull {::core/database transactable
                                 ::core/master-key master-key
                                 ::core/revision-store reader}
                                database-id {})]
      (is @fetched?)
      (is (false? @active?))
      (is (.isClosed ^java.sql.Connection @read-connection))
      (is (= {1 page} (:pages result))))))
