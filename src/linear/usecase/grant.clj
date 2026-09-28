(ns linear.usecase.grant
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.usecase.core :as core]
   [linear.usecase.transaction :as transaction]))

(def ^:private permission-rank
  {:pull  0
   :push  1
   :manage 2})

(defprotocol Store
  (-permission [tx arg-map])
  (-list-grants [tx arg-map])
  (-set-grants! [tx arg-map])
  (-revoke-grants! [tx arg-map]))

(defn permission-includes?
  [{:keys [granted required]}]
  (and (contains? permission-rank granted)
       (contains? permission-rank required)
       (>= (permission-rank granted) (permission-rank required))))

(defn require-permission
  [tx {:keys [actor permission vault-id] :as arg-map}]
  (when-not (permission-includes? {:granted (-permission tx arg-map)
                                   :required permission})
    (throw (ex-info "Vault permission denied"
                    {::anomaly/category ::anomaly/forbidden
                     :reason ::permission-denied
                     :actor actor
                     :vault-id vault-id
                     :permission permission})))
  true)

(defn list-grants
  {:malli/schema [:->
                  :map
                  [:map
                   [:actor [:string {:min 1}]]
                   [:vault-id [:string {:min 1}]]]
                  [:sequential
                   [:map
                    [:subject [:string {:min 1}]]
                    [:permission [:enum :pull :push :manage]]]]]}
  [context {:keys [actor vault-id]}]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    (require-permission tx {:actor actor :vault-id vault-id :permission :manage})
    (-list-grants tx {:vault-id vault-id})))

(defn set-grant!
  {:malli/schema [:->
                  :map
                  [:map
                   [:actor [:string {:min 1}]]
                   [:vault-id [:string {:min 1}]]
                   [:subject [:string {:min 1}]]
                   [:permission [:enum :pull :push :manage]]]
                  :boolean]}
  [context {:keys [actor vault-id subject permission]}]
  (transaction/with-transaction [tx (core/transactable context)]
    (require-permission tx {:actor actor :vault-id vault-id :permission :manage})
    (-set-grants! tx {:data [{:vault-id vault-id
                              :subject subject
                              :permission permission}]})))

(defn revoke-grant!
  {:malli/schema [:->
                  :map
                  [:map
                   [:actor [:string {:min 1}]]
                   [:vault-id [:string {:min 1}]]
                   [:subject [:string {:min 1}]]]
                  :boolean]}
  [context {:keys [actor vault-id subject]}]
  (transaction/with-transaction [tx (core/transactable context)]
    (require-permission tx {:actor actor :vault-id vault-id :permission :manage})
    (-revoke-grants! tx {:data [{:vault-id vault-id :subject subject}]})))
