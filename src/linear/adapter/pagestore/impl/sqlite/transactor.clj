(ns linear.adapter.pagestore.impl.sqlite.transactor
  "SQLite-backed implementation of the internal Transactor protocol."
  (:require
   [clj-ulid :refer [ulid]]
   [cognitect.anomalies :as anomaly]
   [integrant.core :as integrant]
   [linear.adapter.pagestore.impl.core :as pagestore]
   [linear.adapter.pagestore.impl.sqlite.driver :as driver]
   [linear.adapter.pagestore.impl.sqlite.session :as session]))

(defn- sqlite-error [message context throwable]
  (let [data (merge {::anomaly/category ::anomaly/fault}
                    (when (instance? clojure.lang.ExceptionInfo throwable)
                      (ex-data throwable))
                    context)]
    (ex-info message data throwable)))

(defn- anomaly-error [message context throwable]
  (if (and (instance? clojure.lang.ExceptionInfo throwable)
           (contains? (ex-data throwable) ::anomaly/category))
    throwable
    (sqlite-error message context throwable)))

(defn- call-sqlite [sqlite-session context f]
  (try
    (let [result (f)]
      (session/throw-if-failed! sqlite-session)
      result)
    (catch VirtualMachineError throwable
      (throw throwable))
    (catch clojure.lang.ExceptionInfo throwable
      (throw (sqlite-error "SQLite execution failed" context throwable)))
    (catch Throwable throwable
      (throw (sqlite-error "SQLite execution failed" context throwable)))))

(defn- sqlite-result [sqlite-session context f]
  (let [result (call-sqlite sqlite-session context f)]
    (when-not (zero? result)
      (throw (ex-info "SQLite call returned an error"
                      (merge {::anomaly/category ::anomaly/fault
                              :reason            ::sqlite-call-failed
                              :code              result}
                             context))))
    result))

(defn- execution-path []
  (str "/linear/" (random-uuid) ".db"))

(defn- configure [sqlite-session sqlite-db]
  (doseq [sql ["PRAGMA locking_mode=EXCLUSIVE"
               "PRAGMA wal_autocheckpoint=0"
               "PRAGMA synchronous=FULL"]]
    (sqlite-result sqlite-session
                   {:operation :configure}
                   #(driver/execute-sql! sqlite-db sql))))

(defn- revision [database delta]
  (assoc delta
         :revision-id (ulid)
         :parent      (pagestore/revision-id database)))

(defn- execute-operation [sqlite-session sqlite-db operation]
  (let [execute-sql (fn [sql]
                      (sqlite-result sqlite-session
                                     {:operation :execute}
                                     #(driver/execute-sql! sqlite-db sql)))]
    (sqlite-result sqlite-session
                   {:operation :begin}
                   #(driver/execute-sql! sqlite-db "BEGIN IMMEDIATE"))
    (operation execute-sql)
    (sqlite-result sqlite-session
                   {:operation :commit}
                   #(driver/execute-sql! sqlite-db "COMMIT"))))

(defn- attempt [f]
  (try
    {:value (f)}
    (catch VirtualMachineError throwable
      (throw throwable))
    (catch Throwable throwable
      {:error throwable})))

(defn- rethrow-with-suppressed [^clojure.lang.ExceptionInfo primary-error
                                ^clojure.lang.ExceptionInfo cleanup-error]
  (when cleanup-error
    (.addSuppressed primary-error cleanup-error))
  (throw primary-error))

(defn- apply-operation [runtime database operation]
  (let [path           (execution-path)
        sqlite-session (session/new-session database)]
    (driver/register-session! runtime path sqlite-session)
    (try
      (let [sqlite-db (call-sqlite sqlite-session
                                   {:operation :open
                                    :path      path}
                                   #(driver/open-database! path
                                                           driver/sqlite-open-readwrite
                                                           (:name runtime)))
            execution (attempt (fn []
                                 (configure sqlite-session sqlite-db)
                                 (execute-operation sqlite-session sqlite-db operation)
                                 (session/throw-if-failed! sqlite-session)
                                 (revision database (session/canonical-revision sqlite-session))))
            closing   (attempt (fn []
                                 (session/begin-close! sqlite-session)
                                 (sqlite-result sqlite-session
                                                {:operation :close}
                                                #(driver/close-database! sqlite-db))))
            execution-error (when-let [error (:error execution)]
                              (anomaly-error "SQLite virtual execution failed"
                                             {:operation :transact}
                                             error))
            closing-error   (when-let [error (:error closing)]
                              (anomaly-error "SQLite virtual execution cleanup failed"
                                             {:operation :close}
                                             error))]
        (if execution-error
          (rethrow-with-suppressed execution-error closing-error)
          (if closing-error
            (throw closing-error)
            (:value execution))))
      (finally
        (driver/unregister-session! runtime path)))))

(defrecord SqliteTransactor [vfs-runtime]
  pagestore/Transactor
  (-ingest [_ {:keys [database operation]}]
    (apply-operation vfs-runtime database operation))
  (-vacuum [_ {:keys [database operation]}]
    (apply-operation vfs-runtime database operation)))

(defn transactor [vfs-runtime]
  (->SqliteTransactor vfs-runtime))

(defmethod integrant/init-key :linear.adapter.pagestore.impl.sqlite/transactor [_ {:keys [vfs]}]
  (transactor vfs))
