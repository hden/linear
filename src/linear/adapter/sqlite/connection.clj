(ns linear.adapter.sqlite.connection
  (:require
   [coffi.ffi :as coffi]
   [coffi.mem :as mem]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.adapter.sqlite.ffi :as ffi]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.spec :refer [spec-for]])
  (:import
   (java.lang AutoCloseable)))

(def ^:private database-configurations
  [[ffi/sqlite-dbconfig-no-checkpoint-on-close 1]
   [ffi/sqlite-dbconfig-defensive 1]
   [ffi/sqlite-dbconfig-trusted-schema 0]
   [ffi/sqlite-dbconfig-enable-load-extension 0]])

(defonce ^:private authorizer-arena (mem/shared-arena))

(defn- authorize [_context action _argument-1 _argument-2 _database _trigger]
  (if (#{ffi/sqlite-attach ffi/sqlite-detach} action)
    ffi/sqlite-deny
    ffi/sqlite-ok))

(defonce ^:private authorizer
  (mem/serialize authorize
                 [::coffi/fn [::mem/pointer
                              ::mem/int
                              ::mem/c-string
                              ::mem/c-string
                              ::mem/c-string
                              ::mem/c-string]
                  ::mem/int]
                 authorizer-arena))

(defn- sqlite-diagnostics [database code]
  {:code           code
   :extended-code  (ffi/extended-errcode database)
   :sqlite-message (ffi/errmsg database)})

(defrecord Connection [handle closed?]
  AutoCloseable
  (close [_]
    (when (compare-and-set! closed? false true)
      (let [result (ffi/close handle)]
        (when-not (= ffi/sqlite-ok result)
          (reset! closed? false)
          (fault "SQLite database close failed"
                 (merge {:reason ::close-failed}
                        (sqlite-diagnostics handle result))))))))

(defmethod spec-for ::connection [_]
  [:fn #(instance? Connection %)])

(defn- configure! [database]
  (doseq [[option value] database-configurations]
    (let [result (ffi/db-config database option value)]
      (when-not (= ffi/sqlite-ok result)
        (fault "SQLite security configuration failed"
               (merge {:reason ::security-configuration-failed
                       :option option}
                      (sqlite-diagnostics database result))))))
  (let [result (ffi/enable-load-extension database 0)]
    (when-not (= ffi/sqlite-ok result)
      (fault "SQLite extension loading disable failed"
             (merge {:reason ::security-configuration-failed
                     :option :extension-loading}
                    (sqlite-diagnostics database result)))))
  (let [result (ffi/set-authorizer database authorizer mem/null)]
    (when-not (= ffi/sqlite-ok result)
      (fault "SQLite ATTACH authorizer installation failed"
             (merge {:reason ::security-configuration-failed
                     :option :attach}
                    (sqlite-diagnostics database result))))))

(defn open
  {:malli/schema [:->
                  [:map
                   [:path ::sqlite/path]
                   [:vfs-name :string]]
                  ::connection]}
  [{:keys [path vfs-name]}]
  (with-open [arena (mem/confined-arena)]
    (let [output   (mem/alloc (mem/size-of ::mem/pointer) arena)
          result   (ffi/open-v2 path output ffi/sqlite-database-open-flags vfs-name)
          database (mem/read-address output)]
      (if (= ffi/sqlite-ok result)
        (try
          (configure! database)
          (->Connection database (atom false))
          (catch Throwable throwable
            (ffi/close database)
            (throw throwable)))
        (let [diagnostics (when-not (mem/null? database)
                            (sqlite-diagnostics database result))]
          (when-not (mem/null? database)
            (ffi/close database))
          (fault "SQLite database open failed"
                 (merge {:reason ::open-failed
                         :code   result
                         :path   path}
                        diagnostics)))))))

(defn- ensure-open! [connection]
  (when @(:closed? connection)
    (fault "SQLite connection is closed"
           {:reason ::connection-closed})))

(defn execute
  {:malli/schema [:-> ::connection :string :nil]}
  [connection sql]
  (ensure-open! connection)
  (let [result (ffi/exec (:handle connection) sql mem/null mem/null mem/null)]
    (when-not (= ffi/sqlite-ok result)
      (fault "SQLite statement failed"
             (merge {:reason ::statement-failed
                     :sql    sql}
                    (sqlite-diagnostics (:handle connection) result)))))
  nil)

(defn close
  {:malli/schema [:-> ::connection :nil]}
  [connection]
  (.close ^AutoCloseable connection))
