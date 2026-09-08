(ns linear.usecase.database
  (:require
   [clojure.string :as string]
   [cognitect.anomalies :as anomaly]
   [diehard.core :as diehard]
   [labrador.core :as lab]
   [linear.spec :refer [spec-for]]
   [linear.usecase.core :as core]
   [linear.usecase.database.core :as database-core]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]
   [urania.core :as u]))

(defmethod spec-for ::database-id [_]
  [:and :string [:fn #(string/starts-with? % "d-")]])

(defmethod spec-for ::database [_]
  [:map
   [:id ::database-id]
   [:display-name :string]
   [:vault [:map
            [:id :string]
            [:owner [:maybe :string]]
            [:created inst?]
            [:keychain :any]]]])

(defn resolve-by-id [master-key tx database-id]
  (or (u/run!!
        (u/mapcat
          (fn [{:keys [vault-id] :as database}]
            (u/mapcat
              (fn [resolved-vault]
                (lab/traverse
                  (-> database
                      (assoc :vault resolved-vault)
                      (dissoc :vault-id))))
              (vault/retriever vault-id)))
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
       (let [resolved-database# (resolve-by-id (core/master-key context#)
                                               tx#
                                               database-id#)]
         (revisions/with-consistent-view
           [view# (core/consistent-readable context#) resolved-database#]
           (let [~binding (database-core/database
                            resolved-database#
                            {:consistent-view view#
                             :evaluator (core/evaluator context#)})]
             ~@body))))))

(defn- revision-conflict? [error]
  (= ::revisions/revision-conflict (:reason (ex-data error))))

(def ^:private revision-conflict-retry-policy
  {:retry-if (fn [_result error]
               (revision-conflict? error))
   :max-retries 2
   :backoff-ms [10 250 2.0]
   :jitter-factor 0.5})

(defn- ensure-descends-from [parent revision]
  (when-not (= parent (:parent revision))
    (throw (ex-info "Evaluator revision does not descend from the snapshot"
                    {::anomaly/category ::anomaly/fault
                     :reason ::invalid-revision-parent
                     :expected-parent parent
                     :actual-parent (:parent revision)}))))

(defn push!
  [context database-id command]
  (try
    (diehard/with-retry revision-conflict-retry-policy
      (let [[database revision]
            (with-database [database context {:database-id database-id
                                              :read-only true}]
              (let [snapshot (revisions/head database)
                    parent (revisions/revision-id snapshot)
                    revision (evaluator/evaluate
                               (database-core/evaluator database)
                               {:snapshot snapshot
                                :command command})]
                (ensure-descends-from parent revision)
                [database revision]))]
        (revisions/publish-next! (core/revision-writable context)
                                 revision
                                 database)))
    (catch clojure.lang.ExceptionInfo error
      (if (revision-conflict? error)
        (throw (ex-info "Database push conflict"
                         {::anomaly/category ::anomaly/conflict
                          :reason ::push-conflict
                          :attempts (inc (:max-retries revision-conflict-retry-policy))}
                         error))
        (throw error)))))

(defn pull
  [context database-id pull-options]
  (with-database [database context {:database-id database-id
                                    :read-only true}]
    (let [{:keys [client-revision server-revision page-ids]} pull-options
          snapshot (if server-revision
                     (revisions/as-of database server-revision)
                     (revisions/head database))
          target-revision (revisions/revision-id snapshot)
          page-count (revisions/size snapshot)
          changed-page-ids (cond
                             (not client-revision)
                             (set (range 1 (inc page-count)))

                             (= client-revision target-revision)
                             #{}

                             :else
                             (revisions/changes-since database
                                                      target-revision
                                                      client-revision))
          selected-page-ids (if page-ids
                              (set (filter page-ids changed-page-ids))
                              changed-page-ids)]
      {:server-revision target-revision
       :database-page-count page-count
       :pages (revisions/fetch-pages-by-ids snapshot {:ids selected-page-ids})})))
