(ns linear.usecase.database.lifecycle-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres]
   [linear.adapter.slatedb.store]
   [linear.adapter.sqlite.evaluator]
   [linear.test :refer [catch-ex-data run]]
   [linear.usecase.core :as core]
   [linear.usecase.database :as database]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.grant :as grant]
   [linear.usecase.vault :as vault]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as result-set])
  (:import
   (java.util Arrays)))

(def ^:private components
  [:duct.database/sql :duct.migrator/ragtime
   :linear.adapter.slatedb.store/store :linear.adapter.sqlite.evaluator/evaluator
   :linear.adapter.crypto/key-service])

(defn- context [system]
  {::core/database (:duct.database.sql/hikaricp system)
   ::core/revision-store (:linear.adapter.slatedb.store/store system)
   ::core/evaluator (:linear.adapter.sqlite.evaluator/evaluator system)
   ::core/key-service (:linear.adapter.crypto/key-service system)})

(defn- new-vault [ctx actor]
  (first (vault/create! ctx {:actor actor :data [{}] :idempotency-key (str (random-uuid))})))

(defn- create-args [vault-id]
  {:actor "manager" :vault-id vault-id :display-name "Primary" :idempotency-key (str (random-uuid))})

(defn- row-count [ctx table database-id]
  (:count (first (jdbc/execute! (::core/database ctx)
                   [(str "SELECT count(*) AS count FROM " table " WHERE database_id = ?") database-id]
                   {:builder-fn result-set/as-unqualified-maps}))))

(defn- concurrent-results [operations]
  (let [start (promise)
        tasks (mapv (fn [operation]
                      (future @start
                              (try (operation)
                                   (catch clojure.lang.ExceptionInfo error error))))
                    operations)]
    (try
      (deliver start true)
      (mapv #(deref % 10000 ::timeout) tasks)
      (finally
        (deliver start true)
        (doseq [task tasks] (is (not= ::timeout (deref task 10000 ::timeout))))))))

(deftest ^:integration concurrent-creation-and-closure-produce-one-identity-and-tombstone
  (with-system [system (run {:keys components})]
    (let [ctx (context system)
          vault-id (new-vault ctx "manager")
          args (create-args vault-id)
          ids (concurrent-results [#(database/create! ctx args) #(database/create! ctx args)])
          id (first ids)]
      (is (every? string? ids))
      (is (apply = ids))
      (is (= [id] (mapv :id (database/list-by-vault ctx {:actor "manager" :vault-id vault-id}))))
      (is (= [nil nil] (concurrent-results [#(database/close! ctx {:actor "manager" :database-id id})
                                            #(database/close! ctx {:actor "manager" :database-id id})])))
      (is (= 1 (row-count ctx "database_tombstones" id))))))

(deftest ^:integration concurrent-rename-and-closure-leave-a-closed-consistent-resource
  (with-system [system (run {:keys components})]
    (let [ctx (context system)
          id (database/create! ctx (create-args (new-vault ctx "manager")))
          args {:actor "manager" :database-id id}
          [renamed closed] (concurrent-results [#(database/update-attributes! ctx (assoc args :display-name "Renamed"))
                                                #(database/close! ctx args)])
          resource (database/get-by-id ctx args)]
      (is (nil? closed))
      (is (= :closed (:state resource)))
      (if (nil? renamed)
        (is (= "Renamed" (:display-name resource)))
        (do (is (= ::anomaly/conflict (::anomaly/category (ex-data renamed))))
            (is (= "Primary" (:display-name resource)))))
      (is (= 1 (row-count ctx "database_tombstones" id))))))

(deftest ^:integration failed-initialization-rolls-back-resource-and-can-be-retried
  (with-system [system (run {:keys components})]
    (let [ctx (context system)
          args (create-args (new-vault ctx "manager"))
          failure (reify revisions/Initializable
                    (-initialize! [_ _] (throw (ex-info "initialization failed" {::anomaly/category ::anomaly/fault}))))]
      (is (= ::anomaly/fault (::anomaly/category (catch-ex-data #(database/create! (assoc ctx ::core/revision-store failure) args)))))
      (is (empty? (database/list-by-vault ctx {:actor "manager" :vault-id (:vault-id args)})))
      (is (empty? (jdbc/execute! (::core/database ctx)
                    ["SELECT id FROM transactions WHERE actor = ? AND idempotency_key = ?" "manager" (:idempotency-key args)])))
      (let [id (database/create! ctx args)]
        (is (pos? (:database-page-count (database/pull ctx {:actor "manager" :database-id id}))))))))

(deftest ^:integration creation-replay-preserves-identity-and-rejects-key-scope-collisions
  (with-system [system (run {:keys components})]
    (let [ctx (context system)
          vault-id (new-vault ctx "manager")
          other-vault (new-vault ctx "manager")
          args (create-args vault-id)
          id (database/create! ctx args)]
      (is (= ::anomaly/conflict (::anomaly/category (catch-ex-data #(database/create! ctx (assoc args :vault-id other-vault))))))
      (is (= ::anomaly/conflict (::anomaly/category (catch-ex-data #(vault/create! ctx {:actor "manager" :data [{}] :idempotency-key (:idempotency-key args)})))))
      (let [key (str (random-uuid))]
        (vault/create! ctx {:actor "manager" :data [{}] :idempotency-key key})
        (is (= ::anomaly/conflict (::anomaly/category (catch-ex-data #(database/create! ctx (assoc args :idempotency-key key)))))))
      (database/close! ctx {:actor "manager" :database-id id})
      (grant/revoke-grant! ctx {:actor "manager" :vault-id vault-id :subject "manager"})
      (is (= id (database/create! (dissoc ctx ::core/evaluator ::core/revision-store ::core/key-service) args)))
      (is (= ::anomaly/forbidden (::anomaly/category (catch-ex-data #(database/get-by-id ctx {:actor "manager" :database-id id}))))))))

(deftest ^:integration lifecycle-permissions-do-not-require-vault-decryption
  (with-system [system (run {:keys components})]
    (let [ctx (context system)
          vault-id (new-vault ctx "manager")
          id (database/create! ctx (create-args vault-id))
          args {:actor "manager" :database-id id}]
      (doseq [permission [:pull :push]]
        (let [actor (name permission)]
          (grant/set-grant! ctx {:actor "manager" :vault-id vault-id :subject actor :permission permission})
          (is (= :active (:state (database/get-by-id ctx {:actor actor :database-id id}))))
          (doseq [operation [#(database/create! ctx (assoc (create-args vault-id) :actor actor))
                             #(database/update-attributes! ctx {:actor actor :database-id id :display-name "Denied"})
                             #(database/close! ctx {:actor actor :database-id id})]]
            (is (= ::anomaly/forbidden (::anomaly/category (catch-ex-data operation)))))))
      (vault/delete! ctx {:actor "manager" :vault-id vault-id})
      (is (= :active (:state (database/get-by-id (dissoc ctx ::core/key-service) args))))
      (database/update-attributes! ctx (assoc args :display-name "Still visible"))
      (is (= ::anomaly/conflict (::anomaly/category (catch-ex-data #(database/create! ctx (create-args vault-id))))))
      (database/close! ctx args)
      (is (= "Still visible" (:display-name (database/get-by-id ctx args)))))))

(deftest ^:integration closure-and-rename-retain-history-without-duplicate-facts
  (with-system [system (run {:keys components})]
    (let [ctx (context system)
          id (database/create! ctx (create-args (new-vault ctx "manager")))
          args {:actor "manager" :database-id id}]
      (dotimes [_ 2] (database/update-attributes! ctx (assoc args :display-name "Renamed")))
      (is (= 2 (row-count ctx "database_attributes" id)))
      (dotimes [_ 2] (database/close! ctx args))
      (is (= 1 (row-count ctx "database_tombstones" id)))
      (is (= :closed (:state (database/get-by-id ctx args))))
      (is (= ::anomaly/conflict (::anomaly/category (catch-ex-data #(database/update-attributes! ctx (assoc args :display-name "Renamed"))))))
      (is (= ::anomaly/not-found (::anomaly/category (catch-ex-data #(database/get-by-id ctx (assoc args :database-id "d-missing")))))))))

(deftest ^:integration pitr-reencrypts-complete-revisions-into-another-vault
  (with-system [system (run {:keys components})]
    (let [ctx (context system)
          source-vault (new-vault ctx "manager")
          target-vault (new-vault ctx "manager")
          source-id (database/create! ctx (create-args source-vault))
          source-args {:actor "manager" :database-id source-id}
          original (database/pull ctx source-args)]
      (database/push! ctx (assoc source-args :command {:statements [{:sql "CREATE TABLE example (id INTEGER PRIMARY KEY)" :parameters []}]}))
      (let [head (database/pull ctx source-args)]
        (database/close! ctx source-args)
        (doseq [[source expected] [[{:database-id source-id} head]
                                   [{:database-id source-id :revision-id (:server-revision original)} original]]]
          (let [id (database/create! ctx (assoc (create-args target-vault) :source source))
                actual (database/pull ctx {:actor "manager" :database-id id})]
            (is (= (:database-page-count expected) (:database-page-count actual)))
            (is (not= (:server-revision expected) (:server-revision actual)))
            (doseq [[page-id page] (:pages expected)]
              (is (Arrays/equals ^bytes page ^bytes (get-in actual [:pages page-id]))))
            (is (= target-vault (:vault-id (database/get-by-id ctx {:actor "manager" :database-id id}))))))
        (is (= ::anomaly/not-found (::anomaly/category
                                     (catch-ex-data #(database/create! ctx (assoc (create-args target-vault)
                                                                             :source {:database-id source-id :revision-id "r-missing"}))))))
        (grant/set-grant! ctx {:actor "manager" :vault-id target-vault :subject "target-manager" :permission :manage})
        (is (= ::anomaly/forbidden (::anomaly/category
                                     (catch-ex-data #(database/create! ctx (assoc (create-args target-vault) :actor "target-manager"
                                                                             :source {:database-id source-id}))))))
        (vault/delete! ctx {:actor "manager" :vault-id source-vault})
        (is (= ::anomaly/conflict (::anomaly/category
                                    (catch-ex-data #(database/create! ctx (assoc (create-args target-vault) :source {:database-id source-id}))))))))))
