(ns linear.adapter.postgres.vault
  (:require
   [cognitect.anomalies :as anomaly]
   [labrador.core :as lab]
   [linear.adapter.postgres.core :as core]
   [linear.usecase.vault :as vault])
  (:import
   (java.sql Connection)))

(extend-protocol vault/Database
  Connection
  (-create! [tx {:keys [actor data idempotency-key]}]
    (let [txid (core/transaction-id)
          vaults (into []
                       (map (fn [{:keys [id ciphertext encrypted-by]}]
                              [id txid ciphertext encrypted-by]))
                       data)]
      (if (seq (core/query tx {:statement {:insert-into :transactions
                                           :columns     [:id :actor :idempotency-key]
                                           :values      [[txid actor idempotency-key]]
                                           :on-conflict [:actor :idempotency-key]
                                           :do-nothing  true
                                           :returning   [:id]}}))
        (let [result (core/transact!
                       tx
                       {:statements [{:statement {:insert-into :vaults
                                                  :columns     [:id :created-by :ciphertext :encrypted-by]
                                                  :values      vaults
                                                  :returning   [:id]}
                                      :parse-fn  #(map (juxt :id identity) %)}]})]
          {:ids (into (sorted-set) (keys result))
           :created? true})
        (let [rows (core/query tx {:statement {:select     [:v.id]
                                               :from       [[:vaults :v]]
                                               :inner-join [[:transactions :t]
                                                            [:= :v.created-by :t.id]]
                                               :where      [:and
                                                            [:= :t.actor actor]
                                                            [:= :t.idempotency-key idempotency-key]]
                                               :order-by   [[:v.id :asc]]}})]
          (when (empty? rows)
            (throw (ex-info "Idempotency key belongs to another operation"
                            {::anomaly/category ::anomaly/conflict
                             :reason ::core/transaction-idempotency-conflict})))
          {:ids (into (sorted-set) (keep :id rows))
           :created? false}))))
  (-read [tx {:keys [vault-id lock?]}]
    (first (core/query tx {:statement (cond-> {:select [:id [:created-at :created] :ciphertext :encrypted-by]
                                               :from :vaults
                                               :where [:= :id vault-id]}
                                        lock? (assoc :for :update))})))
  (-store-key! [tx {:keys [vault-id ciphertext encrypted-by]}]
    (core/query tx {:statement {:update :vaults
                                :set {:ciphertext ciphertext :encrypted-by encrypted-by}
                                :where [:= :id vault-id]}})))

(lab/defretriever vault
  {:tag :linear.usecase.vault/vault}
  [{:keys [tx]} ids]
  (core/query tx {:statement {:select   [[:v.id :id]
                                         [:v.created-at :created]
                                         [:v.ciphertext :ciphertext]
                                         [:v.encrypted-by :encrypted-by]]
                              :from     [[:vaults :v]]
                              :where    [:in :v.id ids]
                              :order-by [[:v.id :asc]]}
                  :parse-fn (fn [rows]
                              (into {}
                                    (map (juxt :id identity))
                                    rows))}))
