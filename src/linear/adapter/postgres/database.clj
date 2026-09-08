(ns linear.adapter.postgres.database
  (:require
   [labrador.core :as lab]
   [linear.adapter.postgres.core :as postgres]))

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
                 :where      [:in :d.id ids]
                 :order-by   [[:d.id :asc]]}
     :parse-fn (fn [rows]
                 (into {} (map (juxt :id identity)) rows))}))
