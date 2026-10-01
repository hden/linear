(ns linear.usecase.database.push-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.adapter.slatedb.store]
   [linear.adapter.sqlite.evaluator]
   [linear.test :refer [catch-ex-data run]]
   [linear.test-data.postgres :as postgres-data]
   [linear.test-data.slatedb :as slatedb-data]
   [linear.test-data.sqlite :as sqlite-data]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.grant :as grant]
   [linear.usecase.transaction :as transaction]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel]))

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

(defn- sync-command [statements progress]
  {:sync-progress progress
   :statements (into [{:sql "CREATE TABLE IF NOT EXISTS turso_sync_last_change_id (client_id TEXT PRIMARY KEY, pull_gen INTEGER, change_id INTEGER)"
                       :parameters []}]
                 (concat statements
                         [{:sql "INSERT INTO turso_sync_last_change_id(client_id, pull_gen, change_id) VALUES (?, ?, ?) ON CONFLICT(client_id) DO UPDATE SET pull_gen=excluded.pull_gen, change_id=excluded.change_id"
                           :parameters [(:client-id progress) (:generation progress) (:change-id progress)]}]))})

(deftest ^:integration sync-progress-and-data-are-published-once-and-fail-together
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store
                                    :linear.adapter.sqlite.evaluator/evaluator]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          store (:linear.adapter.slatedb.store/store system)
          evaluator (:linear.adapter.sqlite.evaluator/evaluator system)
          {:keys [database-id master-key keychain vault-id]}
          (postgres-data/create-database! {:datasource datasource})
          context (application-context datasource master-key store evaluator)
          args {:actor "actor-1" :database-id database-id}
          progress {:client-id "client" :generation 0 :change-id 1}
          command (sync-command [{:sql "UPDATE t SET value = 'saved' WHERE id = 1" :parameters []}]
                                progress)]
      (slatedb-data/store-root! {:store store :database-id database-id :keychain keychain
                                 :pages (sqlite-data/pages {:image (sqlite-data/sqlite-image)})})
      (is (nil? (database/sync-progress context (assoc args :client-id "client"))))
      (let [revision (database/push! context (assoc args :command command))]
        (is (= progress (database/sync-progress context (assoc args :client-id "client"))))
        (is (nil? (database/push! context (assoc args :command command))))
        (is (= (:revision-id revision) (:server-revision (database/pull context args))))
        (is (thrown? Exception
                     (database/push! context
                       (assoc args :command
                              (sync-command [{:sql "UPDATE t SET value = 'rolled-back' WHERE id = 1" :parameters []}
                                             {:sql "INSERT INTO t VALUES (1, 'duplicate')" :parameters []}]
                                            (assoc progress :change-id 2))))))
        (is (= progress (database/sync-progress context (assoc args :client-id "client"))))
        (is (= (:revision-id revision) (:server-revision (database/pull context args))))
        (grant/set-grant! context {:actor "actor-1" :vault-id vault-id
                                   :subject "reader" :permission :pull})
        (is (= progress (database/sync-progress context (assoc args :actor "reader" :client-id "client"))))
        (is (= ::anomaly/forbidden
               (::anomaly/category
                 (catch-ex-data #(database/push! context (assoc args :actor "reader" :command command))))))
        (is (= ::anomaly/forbidden
               (::anomaly/category
                 (catch-ex-data #(database/sync-progress context (assoc args :actor "unknown" :client-id "client"))))))))))

(deftest ^:integration publication-conflict-rereads-sync-progress-before-replay
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]} (postgres-data/create-database! {:datasource datasource})
          progress {:client-id "client" :generation 0 :change-id 1}
          stored (atom nil)
          evaluations (atom 0)
          publications (atom 0)
          reads (atom 0)
          store (reify revisions/ConsistentReadable
                  (-read-consistently [_ f _] (f (view (atom []) "r-current")))
                  revisions/RevisionWritable
                  (-publish-next! [_ _ _]
                    (swap! publications inc)
                    (reset! stored progress)
                    (throw (ex-info "concurrent publication" {:reason ::revisions/revision-conflict}))))
          evaluator (reify evaluator/SyncProgressReadable
                      (-sync-progress [_ _] (swap! reads inc) @stored)
                      evaluator/Evaluator
                      (-evaluate [_ _]
                        (swap! evaluations inc)
                        {:revision-id "r-next" :parent "r-current" :database-page-count 1 :pages {}}))]
      (is (nil? (database/push! (application-context datasource master-key store evaluator)
                  {:actor "actor-1" :database-id database-id
                   :command {:statements [] :sync-progress progress}})))
      (is (= 2 @reads))
      (is (= 1 @evaluations))
      (is (= 1 @publications)))))

(deftest ^:integration permissions-gate-database-and-grant-operations
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id vault-id master-key]}
          (postgres-data/create-database! {:datasource datasource})
          events    (atom [])
          store     (reify
                      revisions/ConsistentReadable
                      (-read-consistently [_ f _]
                        (swap! events conj :view)
                        (f (view events "r-current")))
                      revisions/RevisionWritable
                      (-publish-next! [_ revision _]
                        (swap! events conj :publish)
                        revision))
          evaluator (reify evaluator/Evaluator
                      (-evaluate [_ _]
                        (swap! events conj :evaluate)
                        {:revision-id "r-next"
                         :parent "r-current"
                         :database-page-count 1
                         :pages {}}))
          context   (application-context datasource master-key store evaluator)]
      (grant/set-grant! context {:actor "actor-1"
                                 :vault-id vault-id
                                 :subject "pull-actor"
                                 :permission :pull})
      (grant/set-grant! context {:actor "actor-1"
                                 :vault-id vault-id
                                 :subject "push-actor"
                                 :permission :push})

      (is (= "r-current"
             (:server-revision (database/pull context {:actor "pull-actor"
                                                       :database-id database-id}))))
      (is (= "r-current"
             (:server-revision (database/pull context {:actor "push-actor"
                                                       :database-id database-id}))))
      (is (= [:view :head :view :head] @events))

      (reset! events [])
      (let [denied-push (catch-ex-data
                          #(database/push! (assoc context ::core/master-key nil)
                                           {:actor "pull-actor"
                                            :database-id database-id
                                            :command {:statements []}}))]
        (is (= ::anomaly/forbidden (::anomaly/category denied-push)))
        (is (= ::grant/permission-denied (:reason denied-push)))
        (is (empty? @events)))
      (is (= ::anomaly/forbidden
             (::anomaly/category
               (catch-ex-data #(grant/list-grants context {:actor "pull-actor"
                                                           :vault-id vault-id})))))
      (is (= ::anomaly/forbidden
             (::anomaly/category
               (catch-ex-data #(grant/list-grants context {:actor "push-actor"
                                                           :vault-id vault-id})))))

      (is (= "r-next"
             (:revision-id (database/push! context {:actor "push-actor"
                                                    :database-id database-id
                                                    :command {:statements []}}))))
      (is (= [:view :head :evaluate :publish] @events)))))

(deftest ^:integration pushes-through-domain-sqlite-and-slatedb-boundaries
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store
                                    :linear.adapter.sqlite.evaluator/evaluator]})]
    (let [datasource  (:duct.database.sql/hikaricp system)
          store       (:linear.adapter.slatedb.store/store system)
          evaluator   (:linear.adapter.sqlite.evaluator/evaluator system)
          {:keys [database-id master-key keychain]}
          (postgres-data/create-database! {:datasource datasource})
          context     {::core/database datasource
                       ::core/master-key master-key
                       ::core/revision-store store
                       ::core/evaluator evaluator}]
      (slatedb-data/store-root! {:store store
                                 :database-id database-id
                                 :keychain keychain
                                 :pages (sqlite-data/pages {:image (sqlite-data/sqlite-image)})})
      (let [first-revision
            (database/push! context {:actor "actor-1" :database-id database-id
                                     :command {:statements [{:sql "UPDATE t SET value = ? WHERE id = 1"
                                                             :parameters ["first"]}]}})
            second-revision
            (database/push! context {:actor "actor-1" :database-id database-id
                                     :command {:statements
                                               [{:sql (str "SELECT CASE WHEN value = ? THEN 1 "
                                                           "ELSE abs(-9223372036854775808) END FROM t WHERE id = 1")
                                                 :parameters ["first"]}
                                                {:sql "UPDATE t SET value = ? WHERE id = 1"
                                                 :parameters ["second"]}]}})]
        (is (= "r-root" (:parent first-revision)))
        (is (= (:revision-id first-revision) (:parent second-revision)))))))

(deftest ^:integration push-uses-the-database-resolved-from-the-application-context
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
      (postgres-data/create-database! {:datasource datasource
                                       :master-key master-key
                                       :keychain keychain
                                       :database-id database-id
                                       :vault-id vault-id
                                       :transaction-id tx-id
                                       :attributes-id attributes-id})
      (let [revision (database/push! context {:actor "actor-1"
                                              :database-id database-id
                                              :command command})]
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
                          :created (get-in database-at-read [:vault :created])
                          :keychain keychain}}
                 (select-keys database-at-read [:id :display-name :vault])))
          (is (inst? (get-in database-at-read [:vault :created])))
          (is (identical? view (model/consistent-view database-at-publish)))
          (is (identical? evaluator (model/evaluator database-at-publish)))
          (is (identical? store (::model/revision-writable database-at-publish))))))))

(deftest ^:integration push-retries-evaluation-after-a-revision-conflict
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (postgres-data/create-database! {:datasource datasource})
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
          result       (database/push! (application-context datasource
                                         master-key
                                         store
                                         evaluator) {:actor "actor-1" :database-id database-id :command {:statements []}})]
      (is (= "r-second-next" (:revision-id result)))
      (is (= ["r-first" "r-second"] @evaluated))
      (is (= ["Primary" "Renamed"] @resolved-names))
      (is (= 2 @publishes)))))

(deftest ^:integration push-does-not-publish-a-revision-with-a-different-parent
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (postgres-data/create-database! {:datasource datasource})
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
                       (database/push! (application-context datasource
                                         master-key
                                         store
                                         evaluator) {:actor "actor-1" :database-id database-id :command {:statements []}})
                       nil
                       (catch clojure.lang.ExceptionInfo failure
                         failure))]
      (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
      (is (= ::database/invalid-revision-parent (-> error ex-data :reason)))
      (is (false? @published?)))))

(deftest ^:integration push-does-not-retry-a-non-conflict-failure
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (postgres-data/create-database! {:datasource datasource})
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
                      (database/push! (application-context datasource
                                        master-key
                                        store
                                        evaluator) {:actor "actor-1" :database-id database-id :command {:statements []}})
                      nil
                      (catch clojure.lang.ExceptionInfo caught
                        caught))]
      (is (identical? failure error))
      (is (= 1 @reads)))))

(deftest ^:integration push-translates-an-exhausted-revision-conflict
  (with-system [system (run {:keys [:duct.database/sql
                                    :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          {:keys [database-id master-key]}
          (postgres-data/create-database! {:datasource datasource})
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
                      (database/push! (application-context datasource
                                        master-key
                                        store
                                        evaluator) {:actor "actor-1" :database-id database-id :command {:statements []}})
                      nil
                      (catch clojure.lang.ExceptionInfo caught
                        caught))]
      (is (= ::anomaly/conflict (-> error ex-data ::anomaly/category)))
      (is (= ::database/push-conflict (-> error ex-data :reason)))
      (is (= 3 (-> error ex-data :attempts)))
      (is (= 3 @publishes)))))
