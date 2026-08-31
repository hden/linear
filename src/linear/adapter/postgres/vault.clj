(ns linear.adapter.postgres.vault
  (:require
   [diehard.core :refer [with-retry with-timeout]]
   [labrador.core :as lab]
   [linear.adapter.postgres.core :as core]
   [linear.usecase.vault :as vault]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel])
  (:import
   (java.sql Connection)
   (javax.sql DataSource)))

;; TODO: switch to a KMS key identified by the vaults.encrypted_by value.
(defonce master-kek (delay (tempel/keychain)))
(def ^:private encrypted-by "placeholder")

(defn- encrypt-keychain []
  (tempel/encrypt-keychain
    (tempel/keychain)
    {:key-sym @master-kek}))

(extend-protocol vault/Transactable
  DataSource
  (-transact [datasource f {:keys [read-only timeout-ms]
                            :or {timeout-ms 2000}}]
    (with-timeout {:timeout-ms timeout-ms :interrupt? true}
      (with-retry core/default-retry-policy
        (jdbc/with-transaction [tx datasource {:isolation :serializable :read-only read-only}]
          (f tx))))))

(extend-protocol vault/Database
  Connection
  (-create! [tx {:keys [data idempotency-key]}]
    (let [txid (core/transaction-id)
          vaults (into []
                       (map (fn [{:keys [id owner]}]
                              [id owner txid (encrypt-keychain) encrypted-by]))
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
  {:tag         :linear.usecase.vault/vault
   :decorate-fn (fn [{:keys [ciphertext] :as vault}]
                  (let [keychain (tempel/keychain-decrypt
                                   ciphertext
                                   {:key-sym @master-kek})]
                    (if keychain
                      (-> vault
                          (assoc :keychain keychain)
                          (dissoc :ciphertext))
                      (throw (ex-info "Vault keychain could not be decrypted"
                                      {:cognitect.anomalies/category :cognitect.anomalies/fault
                                       :reason ::vault-decryption-failed})))))}
  [{:keys [tx]} ids]
  (core/query tx {:statement {:select   [[:v.id :id]
                                         [:v.created-at :created]
                                         [:v.ciphertext :ciphertext]]
                              :from     [[:vaults :v]]
                              :where    [:in :v.id ids]
                              :order-by [[:v.id :asc]]}
                  :parse-fn (fn [rows]
                              (into {}
                                    (map (juxt :id identity))
                                    rows))}))
