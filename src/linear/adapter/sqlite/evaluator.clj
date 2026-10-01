(ns linear.adapter.sqlite.evaluator
  (:require
   [clojure.java.io :as io]
   [cognitect.anomalies :as anomaly]
   [diehard.core :refer [with-timeout]]
   [hden.ulid :refer [ulid]]
   [integrant.core :as integrant]
   [linear.adapter.sqlite.connection :as connection]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.adapter.sqlite.evaluation :as evaluation]
   [linear.adapter.sqlite.vfs :as vfs]
   [linear.spec :refer [spec-for]]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.healthcheck :as healthcheck])
  (:import
   (dev.failsafe TimeoutExceededException)))

(def ^:private ^:const default-shutdown-timeout-ms 10000)

(defmethod spec-for ::evaluator [_]
  [:fn #(satisfies? evaluator/Evaluator %)])

(defn- call-sqlite [invocation context f]
  (try
    (let [result (f)]
      (vfs/throw-if-failed! invocation)
      result)
    (catch Exception error
      (try
        (vfs/throw-if-failed! invocation)
        (catch Exception callback-failure
          (fault "SQLite execution failed" context callback-failure)))
      (fault "SQLite execution failed" context error))))

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
         :revision-id (str "r-" (ulid))
         :parent (revisions/revision-id snapshot)))

(defn- execute-command! [invocation sqlite-database command]
  (execute! invocation sqlite-database {:operation :begin} "BEGIN IMMEDIATE")
  (let [outcome (try
                  (doseq [[statement-index statement]
                          (map-indexed vector (:statements command))]
                    (call-sqlite invocation
                                 {:operation :execute
                                  :statement-index statement-index}
                                 #(connection/execute-statement sqlite-database statement)))
                  (execute! invocation sqlite-database {:operation :commit} "COMMIT")
                  {:value nil}
                  (catch Exception error
                    {:error error}))]
    (when-let [error (:error outcome)]
      (try
        (execute! invocation sqlite-database {:operation :rollback} "ROLLBACK")
        (catch Exception rollback-error
          (.addSuppressed ^Exception error rollback-error)))
      (throw error))))

(defn- begin-evaluation! [state]
  (swap! state
         (fn [current]
           (when-not (= :ready (:status current))
             (throw (ex-info "SQLite evaluator is unavailable"
                             {::anomaly/category ::anomaly/unavailable
                              :status            (:status current)})))
           (update current :active inc))))

(defn- signal-if-drained! [{:keys [status active drained]}]
  (when (and (= :draining status) (zero? active))
    (deliver drained true)))

(defn- end-evaluation! [state]
  (signal-if-drained! (swap! state update :active dec)))

(defn- with-snapshot [evaluator snapshot f]
  (begin-evaluation! (:state evaluator))
  (try
    (let [path (str "/linear/" (ulid) ".db")
          filesystem (evaluation/evaluation snapshot {:path path})
          invocation (vfs/mount (:resources evaluator) {:path path :filesystem filesystem})]
      (try
        (let [database (call-sqlite invocation
                                    {:operation :open :path path}
                                    #(connection/open {:path path
                                                       :vfs-name (:name (:resources evaluator))}))]
          (try
            (configure! invocation database)
            (f invocation database filesystem)
            (finally
              (call-sqlite invocation {:operation :close} #(connection/close database)))))
        (finally
          (vfs/unmount invocation))))
    (finally
      (end-evaluation! (:state evaluator)))))

(defn- read-progress [invocation database client-id]
  (let [[table-count] (call-sqlite invocation {:operation :read-sync-progress}
                        #(connection/query-integers database
                           {:sql "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name = ?"
                            :parameters ["turso_sync_last_change_id"]}))]
    (when (pos? table-count)
      (when-let [[generation change-id]
                 (call-sqlite invocation {:operation :read-sync-progress}
                              #(connection/query-integers database
                                 {:sql "SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?"
                                  :parameters [client-id]}))]
        {:client-id client-id :generation generation :change-id change-id}))))

(defrecord ^:private Evaluator [resources state shutdown-timeout-ms]
  evaluator/Initializer
  (-initial-revision [_]
    (with-open [input (io/input-stream (io/resource "linear/adapter/sqlite/empty.db"))]
      {:revision-id (str "r-" (ulid))
       :parent nil
       :database-page-count 1
       :pages {1 (.readAllBytes ^java.io.InputStream input)}}))
  healthcheck/Checkable
  (-ready? [_]
    (= :ready (:status @state)))
  (-ok? [_]
    (and (= :ok (:liveness @state))
         (vfs/ok? resources)))
  evaluator/SyncProgressReadable
  (-sync-progress [this {:keys [snapshot client-id]}]
    (with-snapshot this snapshot
      (fn [invocation database _]
        (read-progress invocation database client-id))))
  evaluator/Evaluator
  (-evaluate [this {:keys [snapshot command]}]
    (with-snapshot this snapshot
      (fn [invocation database filesystem]
        (execute-command! invocation database command)
        (when-let [delta (evaluation/commit filesystem)]
          (revision snapshot delta))))))

(alter-meta! #'->Evaluator assoc :private true)
(alter-meta! #'map->Evaluator assoc :private true)

(defmethod integrant/init-key :linear.adapter.sqlite.evaluator/evaluator
  [_ {:keys [name shutdown-timeout-ms]
      :or {name "linear-sqlite"
           shutdown-timeout-ms default-shutdown-timeout-ms}}]
  (->Evaluator (vfs/install {:name name})
               (atom {:status :ready
                      :liveness :ok
                      :active 0
                      :drained (promise)})
               shutdown-timeout-ms))

(defmethod integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator
  [_ evaluator]
  (signal-if-drained! (swap! (:state evaluator) assoc :status :draining))
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
             :liveness :failed)))
  nil)
