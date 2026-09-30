(ns linear.usecase.database
  (:require
   [cognitect.anomalies :as anomaly]
   [diehard.core :as diehard]
   [labrador.core :as lab]
   [linear.spec :refer [spec-for]]
   [linear.usecase.core :as core]
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.grant :as grant]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]
   [urania.core :as u]))

(defmethod spec-for ::database-id [_]
  ::model/database-id)

(defmethod spec-for ::database [_]
  ::model/database)

(defn resolve-by-id [tx {:keys [actor database-id master-key permission]}]
  (let [env      {:tx tx
                  :linear.usecase.core/master-key master-key}
        database (u/run!! (lab/fetch ::database database-id) {:env env})]
    (if-let [vault-id (:vault-id database)]
      (do
        (grant/require-permission tx {:actor actor
                                      :vault-id vault-id
                                      :permission permission})
        (-> database
            (assoc :vault (vault/resolve-by-id tx {:vault-id vault-id :master-key master-key}))
            (dissoc :vault-id)))
      (throw (ex-info "Database was not found"
                      {::anomaly/category ::anomaly/not-found
                       :reason ::database-not-found
                       :database-id database-id})))))

(defmacro with-database
  [[binding context params] & body]
  `(let [context# ~context
         params# ~params
         database-id# (:database-id params#)
         actor# (:actor params#)
         permission# (:permission params#)
         read-only# (get params# :read-only false)]
     (transaction/with-transaction
       [tx# (core/transactable context#) {:read-only read-only#}]
       (let [resolved-database# (resolve-by-id tx# {:actor actor#
                                                    :database-id database-id#
                                                    :master-key (core/master-key context#)
                                                    :permission permission#})]
         (revisions/with-consistent-view
           [view# (core/consistent-readable context#) resolved-database#]
           (let [~binding (model/database
                            resolved-database#
                            {::model/consistent-view view#
                             ::model/evaluator (core/evaluator context#)
                             ::model/revision-writable (core/revision-writable context#)})]
             ~@body))))))

(defn- revision-conflict? [error]
  (= ::revisions/revision-conflict (:reason (ex-data error))))

(def ^:private revision-conflict-retry-policy
  {:retry-if (fn [_result error]
               (revision-conflict? error))
   :max-retries 2
   :backoff-ms [10 250 2.0]
   :jitter-factor 0.5})

(defn push!
  [context {:keys [actor database-id command]}]
  (try
    (diehard/with-retry revision-conflict-retry-policy
      (let [[database revision]
            (with-database [database context {:database-id database-id
                                              :actor actor
                                              :permission :push
                                              :read-only true}]
              [database (model/evaluate database command)])]
        (model/publish-next! database revision)))
    (catch clojure.lang.ExceptionInfo error
      (if (revision-conflict? error)
        (throw (ex-info "Database push conflict"
                         {::anomaly/category ::anomaly/conflict
                          :reason ::push-conflict
                          :attempts (inc (:max-retries revision-conflict-retry-policy))}
                         error))
        (throw error)))))

(defn pull
  [context {:keys [actor database-id] :as pull-options}]
  (with-database [database context {:database-id database-id
                                    :actor actor
                                    :permission :pull
                                    :read-only true}]
    (model/pull database pull-options)))

(defn check-permission
  [context {:keys [actor database-id permission]}]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    (let [database (u/run!! (lab/fetch ::database database-id) {:env {:tx tx}})]
      (if-let [vault-id (:vault-id database)]
        (do
          (grant/require-permission tx {:actor actor
                                        :vault-id vault-id
                                        :permission permission})
          (vault/require-active (vault/-read tx {:vault-id vault-id :lock? false})))
        (throw (ex-info "Database permission denied"
                        {::anomaly/category ::anomaly/forbidden
                         :reason ::grant/permission-denied
                         :actor actor
                         :database-id database-id
                         :permission permission}))))
    true))
