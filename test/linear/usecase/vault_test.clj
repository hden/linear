(ns linear.usecase.vault-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [duct.test :refer [with-system]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.postgres]
   [linear.adapter.postgres.core :as postgres]
   [linear.test :refer [run]]
   [linear.usecase.core :as core]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.grant :as grant]
   [linear.usecase.healthcheck :as healthcheck]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel])
  (:import
   (java.util.concurrent CountDownLatch TimeUnit)))

(defn- checkable-evaluator []
  (reify
    healthcheck/Checkable
    (-ready? [_] true)
    (-ok? [_] true)
    evaluator/Evaluator
    (-evaluate [_ _] nil)))

(defn- database-store []
  (reify
    revisions/ConsistentReadable
    (-read-consistently [_ f _] (f nil))
    revisions/RevisionWritable
    (-publish-next! [_ revision _] revision)))

(defn- context [datasource]
  {::core/database       datasource
   ::core/evaluator      (checkable-evaluator)
   ::core/revision-store (database-store)
   ::core/keychain       crypto/new-keychain
   ::core/master-key     (crypto/keychain "dev-ephemeral" (tempel/keychain))})

(defn- row-count [datasource table where]
  (postgres/query datasource {:statement {:select [[[:count :*] :count]]
                                          :from table
                                          :where where}
                              :parse-fn #(get-in % [0 :count])}))

(defn- fail-after-initial-grants [datasource created-ids]
  (reify transaction/Transactable
    (-transact [_ f options]
      (transaction/-transact
        datasource
        (fn [tx]
          (f (reify
               vault/Database
               (-create! [_ arg-map]
                 (vault/-create! tx arg-map))
               grant/Store
               (-permission [_ arg-map]
                 (grant/-permission tx arg-map))
               (-list-grants [_ arg-map]
                 (grant/-list-grants tx arg-map))
               (-set-grants! [_ arg-map]
                 (grant/-set-grants! tx arg-map)
                 (reset! created-ids (mapv :vault-id (:data arg-map)))
                 (throw (ex-info "Forced grant failure"
                                 {:reason ::forced-grant-failure})))
               (-revoke-grants! [_ arg-map]
                 (grant/-revoke-grants! tx arg-map)))))
        options))))

(deftest ^:integration vault-creation-is-encrypted-and-idempotent
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp sys)
          context (context datasource)
          key     (str "vault-test-" (random-uuid))
          created-ids (vault/create! context
                                     {:actor "actor-1"
                                      :data [{}]
                                      :idempotency-key key})
          replayed-ids (vault/create! context
                                      {:actor "actor-1"
                                       :data [{}]
                                       :idempotency-key key})
          id          (first created-ids)
          fetched     (vault/get-by-ids context {:actor "actor-1"
                                                 :ids created-ids})
          vault       (get fetched id)]
      (is (= created-ids replayed-ids))
      (is (= id (:id vault)))
      (is (contains? vault :keychain))
      (is (not (contains? vault :ciphertext)))
      (is (not (contains? vault :encrypted-by)))
      (is (= [{:subject "actor-1" :permission :manage}]
             (grant/list-grants context {:actor "actor-1" :vault-id (:id vault)})))
      (let [stored (first (jdbc/execute! datasource
                            ["SELECT ciphertext, encrypted_by FROM vaults WHERE id = ?"
                             (:id vault)]))]
        (is (bytes? (:vaults/ciphertext stored)))
        (is (= (keychain/id (core/master-key context))
               (:vaults/encrypted_by stored))))
      (is (every? (comp keychain/keychain? :keychain) (vals fetched))))))

(deftest ^:integration replay-after-creator-revocation-does-not-restore-grant
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp sys)
          context    (context datasource)
          key        (str "vault-replay-" (random-uuid))
          created-ids (vault/create! context {:actor "actor-1"
                                              :data [{}]
                                              :idempotency-key key})
          id          (first created-ids)]
      (grant/revoke-grant! context {:actor "actor-1"
                                    :vault-id id
                                    :subject "actor-1"})
      (let [replayed-ids (vault/create! context {:actor "actor-1"
                                                 :data [{}]
                                                 :idempotency-key key})
            read-error   (try
                           (vault/get-by-ids context {:actor "actor-1" :ids [id]})
                           nil
                           (catch clojure.lang.ExceptionInfo failure
                             failure))]
        (is (= created-ids replayed-ids))
        (is (= ::anomaly/forbidden (-> read-error ex-data ::anomaly/category)))
        (is (= 1 (row-count datasource
                            :transactions
                            [:and
                             [:= :actor "actor-1"]
                             [:= :idempotency-key key]])))
        (is (= 1 (row-count datasource :vaults [:= :id id])))
        (is (zero? (row-count datasource
                              :vault-grants
                              [:and
                               [:= :vault-id id]
                               [:= :subject "actor-1"]])))))))

(deftest ^:integration initial-grant-failure-rolls-back-vault-creation
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource   (:duct.database.sql/hikaricp sys)
          context      (context datasource)
          actor        (str "rollback-actor-" (random-uuid))
          key          (str "vault-rollback-" (random-uuid))
          created-ids  (atom nil)
          database     (fail-after-initial-grants datasource created-ids)
          failure-data (try
                         (vault/create! (assoc context ::core/database database)
                                        {:actor actor
                                         :data [{}]
                                         :idempotency-key key})
                         nil
                         (catch clojure.lang.ExceptionInfo failure
                           (ex-data failure)))
          vault-id     (first @created-ids)]
      (is (= ::forced-grant-failure (:reason failure-data)))
      (is (string? vault-id))
      (is (zero? (row-count datasource
                            :transactions
                            [:and
                             [:= :actor actor]
                             [:= :idempotency-key key]])))
      (is (zero? (row-count datasource :vaults [:= :id vault-id])))
      (is (zero? (row-count datasource :vault-grants [:= :vault-id vault-id]))))))

(deftest ^:integration concurrent-idempotent-creation-returns-one-vault
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp sys)
          context    (context datasource)
          actor      (str "concurrent-actor-" (random-uuid))
          key        (str "vault-concurrent-" (random-uuid))
          ready      (CountDownLatch. 2)
          start      (CountDownLatch. 1)
          done       (CountDownLatch. 2)
          calls      (mapv (fn [_]
                             (future
                               (.countDown ready)
                               (try
                                 (.await start)
                                 {:ids (vault/create! context {:actor actor
                                                               :data [{}]
                                                               :idempotency-key key})}
                                 (catch Exception error
                                   {:error error})
                                 (finally
                                   (.countDown done)))))
                           (range 2))]
      (try
        (is (.await ready 5 TimeUnit/SECONDS))
        (.countDown start)
        (let [timeout (Object.)
              results (mapv #(deref % 10000 timeout) calls)]
          (is (not-any? #(identical? timeout %) results))
          (is (every? :ids results)
              (pr-str (map #(some-> % :error ex-data) results)))
          (when (every? :ids results)
            (let [created (mapv :ids results)
                  id      (-> created first first)]
              (is (= (first created) (second created)))
              (is (= 1 (row-count datasource
                                  :transactions
                                  [:and
                                   [:= :actor actor]
                                   [:= :idempotency-key key]])))
              (is (= 1 (row-count datasource :vaults [:= :id id])))
              (is (= [{:subject actor :permission :manage}]
                     (grant/list-grants context {:actor actor :vault-id id}))))))
        (finally
          (.countDown start)
          (.await done)
          (doseq [call calls]
            @call))))))

(deftest ^:integration idempotency-keys-are-scoped-by-actor
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp sys)
          context    (context datasource)
          key        (str "vault-actors-" (random-uuid))
          first-ids  (vault/create! context {:actor "actor-1"
                                             :data [{}]
                                             :idempotency-key key})
          second-ids (vault/create! context {:actor "actor-2"
                                             :data [{}]
                                             :idempotency-key key})
          first-id   (first first-ids)
          second-id  (first second-ids)]
      (is (not= first-id second-id))
      (is (= 2 (row-count datasource :transactions [:= :idempotency-key key])))
      (is (= [{:subject "actor-1" :permission :manage}]
             (grant/list-grants context {:actor "actor-1" :vault-id first-id})))
      (is (= [{:subject "actor-2" :permission :manage}]
             (grant/list-grants context {:actor "actor-2" :vault-id second-id}))))))

(deftest ^:integration vault-access-is-isolated-and-revocation-removes-access
  (with-system [sys (run {:keys [:duct.database/sql
                                 :duct.migrator/ragtime]})]
    (let [datasource (:duct.database.sql/hikaricp sys)
          context    (context datasource)
          id         (first (vault/create! context
                                           {:actor "actor-1"
                                            :data [{}]
                                            :idempotency-key (str (random-uuid))}))
          denied     (try
                       (vault/get-by-ids (assoc context ::core/master-key nil)
                                         {:actor "actor-2" :ids [id]})
                       nil
                       (catch clojure.lang.ExceptionInfo error error))]
      (is (= ::anomaly/forbidden (-> denied ex-data ::anomaly/category)))
      (grant/set-grant! context {:actor "actor-1"
                                 :vault-id id
                                 :subject "actor-2"
                                 :permission :pull})
      (is (contains? (vault/get-by-ids context {:actor "actor-2" :ids [id]}) id))
      (grant/revoke-grant! context {:actor "actor-1"
                                    :vault-id id
                                    :subject "actor-2"})
      (let [replayed (try
                       (vault/get-by-ids context {:actor "actor-2" :ids [id]})
                       nil
                       (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::anomaly/forbidden (-> replayed ex-data ::anomaly/category)))))))
