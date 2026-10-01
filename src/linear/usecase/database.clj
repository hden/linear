(ns linear.usecase.database
  (:require
   [cognitect.anomalies :as anomaly]
   [diehard.core :as diehard]
   [hden.ulid :refer [ulid]]
   [labrador.core :as lab]
   [linear.spec :refer [spec-for]]
   [linear.usecase.core :as core]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.grant :as grant]
   [linear.usecase.transaction :as transaction]
   [linear.usecase.vault :as vault]
   [urania.core :as u]))

(defprotocol Store
  (-creation-result [tx arg-map])
  (-create! [tx arg-map])
  (-read [tx arg-map])
  (-list [tx arg-map])
  (-update-attributes! [tx arg-map])
  (-close! [tx arg-map]))

(defmethod spec-for ::resource [_]
  [:map [:id ::model/database-id] [:vault-id :string]
   [:display-name :string] [:state [:enum :active :closed]]])

(defn- resource-not-found [id]
  (ex-info "Database was not found"
           {::anomaly/category ::anomaly/not-found
            :reason ::database-not-found :database-id id}))

(defn- authorized-resource [tx {:keys [actor database-id] :as arg-map} permission]
  (let [resource (or (-read tx arg-map) (throw (resource-not-found database-id)))]
    (grant/require-permission tx {:actor actor :vault-id (:vault-id resource) :permission permission})
    resource))

(defn get-by-id
  {:malli/schema [:-> :map [:map [:actor :string] [:database-id ::model/database-id]] ::resource]}
  [context arg-map]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    (authorized-resource tx arg-map :pull)))

(defn list-by-vault
  {:malli/schema [:-> :map
                  [:map [:actor :string] [:vault-id :string]
                   [:state {:optional true} [:enum :active :closed :all]]]
                  [:vector ::resource]]}
  [context {:keys [actor vault-id] :as arg-map}]
  (transaction/with-transaction [tx (core/transactable context) {:read-only true}]
    (grant/require-permission tx {:actor actor :vault-id vault-id :permission :pull})
    (-list tx (merge {:state :active} arg-map))))

(defn- require-open [resource]
  (when (= :closed (:state resource))
    (throw (ex-info "Database is closed"
                    {::anomaly/category ::anomaly/conflict :reason ::database-closed
                     :database-id (:id resource)}))))

(defn update-attributes!
  {:malli/schema [:-> :map
                  [:map [:actor :string] [:database-id ::model/database-id]
                   [:display-name [:string {:min 1}]]] :nil]}
  [context {:keys [display-name] :as arg-map}]
  (transaction/with-transaction [tx (core/transactable context)]
    (let [resource (authorized-resource tx (assoc arg-map :lock? true) :manage)]
      (require-open resource)
      (when-not (= display-name (:display-name resource))
        (-update-attributes! tx arg-map)))
    nil))

(defn close!
  {:malli/schema [:-> :map [:map [:actor :string] [:database-id ::model/database-id]] :nil]}
  [context arg-map]
  (transaction/with-transaction [tx (core/transactable context)]
    (let [resource (authorized-resource tx (assoc arg-map :lock? true) :manage)]
      (when (= :active (:state resource)) (-close! tx arg-map)))
    nil))

(defn- recovery-root-revision [tx context actor {:keys [revision-id] :as source}]
  (let [resource (authorized-resource tx (assoc source :actor actor) :pull)
        source-database (assoc resource :vault (vault/resolve-by-id tx {:vault-id (:vault-id resource)
                                                                        :master-key (core/master-key context)}))]
    (revisions/with-consistent-view [view (core/consistent-readable context) source-database]
      (let [snapshot (if revision-id (revisions/as-of view revision-id) (revisions/head view))
            size (revisions/size snapshot)]
        {:revision-id (str "r-" (ulid)) :parent nil :database-page-count size
         :pages (revisions/fetch-pages-by-ids snapshot {:ids (set (range 1 (inc size)))})}))))

(defn- creation-id [result vault-id]
  (when-not (= vault-id (:vault-id result))
    (throw (ex-info "Idempotency key belongs to another creation"
                    {::anomaly/category ::anomaly/conflict :reason ::creation-key-conflict})))
  (:id result))

(defn create!
  {:malli/schema [:-> :map
                  [:map [:actor :string] [:vault-id :string] [:display-name [:string {:min 1}]]
                   [:idempotency-key [:string {:min 1}]]
                   [:source {:optional true} [:map [:database-id ::model/database-id]
                                              [:revision-id {:optional true} ::revisions/revision-id]]]]
                  ::model/database-id]}
  [context {:keys [actor vault-id source] :as arg-map}]
  (transaction/with-transaction [tx (core/transactable context)]
    (if-let [result (-creation-result tx arg-map)]
      (creation-id result vault-id)
      (do
        (grant/require-permission tx {:actor actor :vault-id vault-id :permission :manage})
        (vault/require-active (vault/-read tx {:vault-id vault-id :lock? true}))
        (let [target-vault (vault/resolve-by-id tx {:vault-id vault-id :master-key (core/master-key context)})
              {:keys [created?] :as result} (-create! tx arg-map)
              id (creation-id result vault-id)]
          (when created?
            (revisions/initialize! (core/revision-writable context)
              {:database {:id id :vault target-vault}
               :revision (if source (recovery-root-revision tx context actor source)
                             (evaluator/initial-revision (core/evaluator context)))}))
          id)))))

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
                             ::model/sync-progress-reader (core/sync-progress-reader context#)
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
        (when revision
          (model/publish-next! database revision))))
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

(defn sync-progress
  [context {:keys [actor database-id] :as arg-map}]
  (with-database [database context {:database-id database-id
                                    :actor actor
                                    :permission :pull
                                    :read-only true}]
    (model/sync-progress database arg-map)))

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
