(ns linear.adapter.postgres.database
  (:require
   [cognitect.anomalies :as anomaly]
   [hden.ulid :refer [ulid]]
   [labrador.core :as lab]
   [linear.adapter.postgres.core :as postgres]
   [linear.usecase.database :as database])
  (:import
   (java.sql Connection)))

(defn- tombstone-query []
  {:select [1] :from [[:database-tombstones :t]] :where [:= :t.database-id :d.id]})

(defn- resource-query []
  {:select [:d.id [:d.encrypted-by :vault-id] :a.display-name
            [[:case [:exists (tombstone-query)] "closed" :else "active"] :state]]
   :from [[:databases :d]]
   :inner-join [[:database-attributes :a] [:= :a.id :d.current-attributes]]})

(defn- resources [rows]
  (mapv #(update % :state keyword) rows))

(defn- transaction! [tx actor]
  (let [id (postgres/transaction-id)]
    (postgres/query tx {:statement {:insert-into :transactions :columns [:id :actor] :values [[id actor]]}})
    id))

(defn- creation-result [tx {:keys [actor idempotency-key]}]
  (when-let [transaction (first (postgres/query tx {:statement {:select [:id] :from :transactions
                                                                :where [:and [:= :actor actor]
                                                                        [:= :idempotency-key idempotency-key]]}}))]
    (or (first (postgres/query tx {:statement {:select [:d.id [:d.encrypted-by :vault-id]]
                                               :from [[:database-attributes :a]]
                                               :inner-join [[:databases :d] [:= :d.id :a.database-id]]
                                               :where [:= :a.created-by (:id transaction)]}}))
        (throw (ex-info "Idempotency key belongs to another operation"
                        {::anomaly/category ::anomaly/conflict :reason ::database/creation-key-conflict})))))

(extend-protocol database/Store
  Connection
  (-creation-result [tx arg-map]
    (creation-result tx arg-map))
  (-create! [tx {:keys [actor vault-id display-name idempotency-key] :as arg-map}]
    (let [transaction-id (postgres/transaction-id)
          claimed (postgres/query tx {:statement {:insert-into :transactions
                                                  :columns [:id :actor :idempotency-key]
                                                  :values [[transaction-id actor idempotency-key]]
                                                  :on-conflict [:actor :idempotency-key] :do-nothing true :returning [:id]}})]
      (if (seq claimed)
        (let [id (str "d-" (ulid))
              attributes-id (str "a-" (ulid))]
          (postgres/query tx {:statement {:insert-into :databases :columns [:id :encrypted-by :current-attributes]
                                          :values [[id vault-id attributes-id]]}})
          (postgres/query tx {:statement {:insert-into :database-attributes
                                          :columns [:id :database-id :display-name :created-by]
                                          :values [[attributes-id id display-name transaction-id]]}})
          {:id id :vault-id vault-id :created? true})
        (creation-result tx arg-map))))
  (-read [tx {:keys [database-id lock?]}]
    (first (postgres/query tx {:statement (cond-> (assoc (resource-query) :where [:= :d.id database-id])
                                            lock? (assoc :for :update))
                               :parse-fn resources})))
  (-list [tx {:keys [vault-id state]}]
    (postgres/query tx {:statement (assoc (resource-query)
                                          :where (cond-> [:and [:= :d.encrypted-by vault-id]]
                                                   (= state :active) (conj [:not [:exists (tombstone-query)]])
                                                   (= state :closed) (conj [:exists (tombstone-query)]))
                                          :order-by [[:d.id :asc]])
                        :parse-fn resources}))
  (-update-attributes! [tx {:keys [actor database-id display-name]}]
    (let [transaction-id (transaction! tx actor)
          attributes-id (str "a-" (ulid))]
      (postgres/query tx {:statement {:insert-into :database-attributes
                                      :columns [:id :database-id :display-name :created-by]
                                      :values [[attributes-id database-id display-name transaction-id]]}})
      (postgres/query tx {:statement {:update :databases :set {:current-attributes attributes-id}
                                      :where [:= :id database-id]}})))
  (-close! [tx {:keys [actor database-id]}]
    (let [transaction-id (transaction! tx actor)]
      (postgres/query tx {:statement {:insert-into :database-tombstones
                                      :columns [:id :database-id :created-by]
                                      :values [[(str "t-" (ulid)) database-id transaction-id]]}}))))

(lab/defretriever database
  {:tag :linear.usecase.database/database}
  [{:keys [tx]} ids]
  (postgres/query
    tx
    {:statement {:select     [[:d.id :id]
                              [:a.display-name :display-name]
                              [:d.encrypted-by :vault-id]]
                 :from       [[:databases :d]]
                 :inner-join [[:database-attributes :a]
                              [:= :a.id :d.current-attributes]]
                 :where      [:and [:in :d.id ids] [:not [:exists (tombstone-query)]]]
                 :order-by   [[:d.id :asc]]}
     :parse-fn (fn [rows]
                 (into {} (map (juxt :id identity)) rows))}))
