(ns linear.adapter.sqlite.evaluator
  (:require
   [clj-ulid :refer [ulid]]
   [cognitect.anomalies :as anomaly]
   [diehard.core :refer [with-timeout]]
   [integrant.core :as integrant]
   [linear.adapter.sqlite.connection :as connection]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.adapter.sqlite.evaluation :as evaluation]
   [linear.adapter.sqlite.vfs :as vfs]
   [linear.protocol :as protocol]
   [linear.spec :refer [spec-for]])
  (:import
   (dev.failsafe TimeoutExceededException)))

(def ^:private ^:const default-shutdown-timeout-ms 10000)

(defmethod spec-for ::evaluator [_]
  [:fn #(satisfies? protocol/Evaluator %)])

(defn- call-sqlite [invocation context f]
  (try
    (let [result (f)]
      (vfs/throw-if-failed! invocation)
      result)
    (catch VirtualMachineError throwable
      (throw throwable))
    (catch Throwable throwable
      (try
        (vfs/throw-if-failed! invocation)
        (catch VirtualMachineError callback-failure
          (throw callback-failure))
        (catch Throwable callback-failure
          (fault "SQLite execution failed" context callback-failure)))
      (fault "SQLite execution failed" context throwable))))

(defn- execute! [invocation database context sql]
  (call-sqlite invocation context #(connection/execute database sql)))

(defn- configure! [invocation database]
  (doseq [sql ["PRAGMA locking_mode=EXCLUSIVE"
               "PRAGMA wal_autocheckpoint=0"
               "PRAGMA temp_store=MEMORY"
               "PRAGMA synchronous=FULL"]]
    (execute! invocation database {:operation :configure} sql)))

(defn- revision [snapshot delta]
  (assoc delta
         :revision-id (ulid)
         :parent (protocol/revision-id snapshot)))

(defn- begin-evaluation! [state]
  (swap! state
         (fn [current]
           (when-not (= :ready (:status current))
             (throw (ex-info "SQLite evaluator is unavailable"
                             {::anomaly/category ::anomaly/unavailable
                              :status            (:status current)})))
           (update current :active inc))))

(defn- end-evaluation! [state]
  (let [drained (volatile! nil)]
    (swap! state
           (fn [current]
             (let [next-state (update current :active dec)]
               (when (and (= :draining (:status next-state))
                          (zero? (:active next-state)))
                 (vreset! drained (:drained next-state)))
               next-state)))
    (when-let [signal @drained]
      (deliver signal true))))

(defrecord ^:private Evaluator [resources state shutdown-timeout-ms]
  protocol/Checkable
  (-ready? [_]
    (= :ready (:status @state)))
  (-ok? [_]
    (and (= :ok (:liveness @state))
         (vfs/ok? resources)))
  protocol/Evaluator
  (-eval [_ {:keys [snapshot operation]}]
    (begin-evaluation! state)
    (try
      (let [path       (str "/linear/" (ulid) ".db")
            filesystem (evaluation/evaluation snapshot path)
            invocation (vfs/mount resources {:path path :filesystem filesystem})]
        (try
          (let [database (call-sqlite invocation
                                      {:operation :open :path path}
                                      #(connection/open {:path path
                                                         :vfs-name (:name resources)}))]
            (try
              (configure! invocation database)
              (execute! invocation database {:operation :begin} "BEGIN IMMEDIATE")
              (operation #(execute! invocation database {:operation :execute} %))
              (execute! invocation database {:operation :commit} "COMMIT")
              (revision snapshot (evaluation/commit filesystem))
              (finally
                (call-sqlite invocation
                             {:operation :close}
                             #(connection/close database)))))
          (finally
            (vfs/unmount invocation))))
      (finally
        (end-evaluation! state))))
  (-vacuum [this {:keys [snapshot]}]
    (protocol/-eval this
                    {:snapshot snapshot
                     :operation (fn [execute]
                                  (execute "PRAGMA incremental_vacuum"))})))

(alter-meta! #'->Evaluator assoc :private true)
(alter-meta! #'map->Evaluator assoc :private true)

(defmethod integrant/init-key :linear.adapter.sqlite.evaluator/evaluator
  [_ {:keys [library name shutdown-timeout-ms]
      :or {name "linear-sqlite"
           shutdown-timeout-ms default-shutdown-timeout-ms}}]
  (->Evaluator (vfs/install {:library library :name name})
               (atom {:status :ready
                      :liveness :ok
                      :active 0
                      :drained (promise)})
               shutdown-timeout-ms))

(defmethod integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator
  [_ evaluator]
  (let [drained (volatile! nil)]
    (swap! (:state evaluator)
           (fn [state]
             (let [next-state (assoc state :status :draining)]
               (when (zero? (:active next-state))
                 (vreset! drained (:drained next-state)))
               next-state)))
    (when-let [signal @drained]
      (deliver signal true))
    (let [drained? (try
                     (with-timeout {:timeout-ms (:shutdown-timeout-ms evaluator)
                                    :interrupt? true}
                       @(:drained @(:state evaluator)))
                     true
                     (catch TimeoutExceededException _
                       false))]
      (if drained?
        (do
          (vfs/drain! (:resources evaluator))
          (vfs/uninstall (:resources evaluator))
          (swap! (:state evaluator) assoc :status :closed))
        (swap! (:state evaluator) assoc
               :status :abandoned
               :liveness :failed))))
  nil)
