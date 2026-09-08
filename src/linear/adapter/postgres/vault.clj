(ns linear.adapter.postgres.vault
  (:require
   [labrador.core :as lab]
   [linear.adapter.postgres.core :as core]
   [linear.usecase.vault :as vault])
  (:import
   (java.sql Connection)))

(extend-protocol vault/Database
  Connection
  (-create! [tx {:keys [data idempotency-key]}]
    (let [txid (core/transaction-id)
          vaults (into []
                       (map (fn [{:keys [id owner ciphertext encrypted-by]}]
                              [id owner txid ciphertext encrypted-by]))
                       data)]
      (try
        (let [result (core/transact!
                       tx
                       {:statements [{:statement {:insert-into :transactions
                                                  :columns     [:id :idempotency-key]
                                                  :values      [[txid idempotency-key]]}
                                      :parse-fn  (constantly [])}
                                     {:statement {:insert-into :vaults
                                                  :columns     [:id :owner :created-by :ciphertext :encrypted-by]
                                                  :values      vaults
                                                  :returning   [:id]}
                                      :parse-fn  #(map (juxt :id identity) %)}]})]
          (into (sorted-set) (keys result)))
        (catch Exception ex
          (if (core/transaction-idempotency-conflict? ex)
            (do
              (core/rollback tx)
              (let [rows (core/query tx {:statement {:select     [:v.id]
                                                     :from       [[:vaults :v]]
                                                     :inner-join [[:transactions :t]
                                                                  [:= :v.created-by :t.id]]
                                                     :where      [:= :t.idempotency-key idempotency-key]
                                                     :order-by   [[:v.id :asc]]}})]
                (if (seq rows)
                  (into (sorted-set) (keep :id rows))
                  (throw ex))))
            (throw ex)))))))

(lab/defretriever vault
  {:tag :linear.usecase.vault/vault}
  [{:keys [tx]} ids]
  (core/query tx {:statement {:select   [[:v.id :id]
                                         [:v.owner :owner]
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
