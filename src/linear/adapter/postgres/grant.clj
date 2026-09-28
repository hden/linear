(ns linear.adapter.postgres.grant
  (:require
   [clojure.set :as set]
   [linear.adapter.postgres.core :as core]
   [linear.usecase.grant :as grant])
  (:import
   (java.sql Connection)))

(def ^:private permission-names
  {:pull "pull"
   :push "push"
   :manage "manage"})

(def ^:private permission-keywords
  (set/map-invert permission-names))

(extend-protocol grant/Store
  Connection
  (-permission [tx {:keys [actor vault-id]}]
    (some-> (core/query tx {:statement {:select [:permission]
                                        :from     :vault-grants
                                        :where    [:and
                                                   [:= :vault-id vault-id]
                                                   [:= :subject actor]]}})
            first
            :permission
            permission-keywords))

  (-list-grants [tx {:keys [vault-id]}]
    (mapv (fn [{:keys [subject permission]}]
            {:subject subject
             :permission (permission-keywords permission)})
          (core/query tx {:statement {:select   [:subject :permission]
                                      :from     :vault-grants
                                      :where    [:= :vault-id vault-id]
                                      :order-by [[:subject :asc]]}})))

  (-set-grants! [tx {:keys [data]}]
    (when (seq data)
      (core/query tx {:statement {:insert-into  :vault-grants
                                  :columns      [:vault-id :subject :permission]
                                  :values       (mapv (fn [{:keys [vault-id subject permission]}]
                                                        [vault-id subject (permission-names permission)])
                                                      data)
                                  :on-conflict  [:vault-id :subject]
                                  :do-update-set {:permission :excluded.permission}}}))
    true)

  (-revoke-grants! [tx {:keys [data]}]
    (when (seq data)
      (core/query tx {:statement {:delete-from :vault-grants
                                  :where       (into [:or]
                                                     (map (fn [{:keys [vault-id subject]}]
                                                            [:and
                                                             [:= :vault-id vault-id]
                                                             [:= :subject subject]]))
                                                     data)}}))
    true))
