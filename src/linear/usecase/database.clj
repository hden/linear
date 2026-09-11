(ns linear.usecase.database
  (:require
   [cognitect.anomalies :as anomaly]
   [diehard.core :as diehard]
   [labrador.core :as lab]
   [linear.spec :refer [spec-for]]
   [linear.usecase.core :as core]
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]
   [urania.core :as u]))

(defmethod spec-for ::database-id [_]
  ::model/database-id)

(defmethod spec-for ::database [_]
  ::model/database)

(defn resolve-by-id [tx {:keys [master-key database-id]}]
  (or (u/run!!
        (u/mapcat
          (fn [{:keys [vault-id] :as database}]
            (if database
              (u/mapcat
                (fn [resolved-vault]
                  (lab/traverse
                    (-> database
                        (assoc :vault resolved-vault)
                        (dissoc :vault-id))))
                (vault/retriever vault-id))
              (u/value nil)))
          (lab/fetch ::database database-id))
        {:env {:tx tx
               :linear.usecase.core/master-key master-key}})
      (throw (ex-info "Database was not found"
                      {::anomaly/category ::anomaly/not-found
                       :reason ::database-not-found
                       :database-id database-id}))))

(defmacro with-database
  [[binding context params] & body]
  `(let [context# ~context
         params# ~params
         database-id# (:database-id params#)
         read-only# (get params# :read-only false)]
     (transaction/with-transaction
       [tx# (core/transactable context#) {:read-only read-only#}]
       (let [resolved-database# (resolve-by-id tx# {:master-key (core/master-key context#) :database-id database-id#})]
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
  [context {:keys [database-id command]}]
  (try
    (diehard/with-retry revision-conflict-retry-policy
      (let [[database revision]
            (with-database [database context {:database-id database-id
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
  [context {:keys [database-id] :as pull-options}]
  (with-database [database context {:database-id database-id
                                    :read-only true}]
    (model/pull database pull-options)))
