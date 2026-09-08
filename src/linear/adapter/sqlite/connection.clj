(ns linear.adapter.sqlite.connection
  (:require
   [coffi.ffi :as coffi]
   [coffi.mem :as mem]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.adapter.sqlite.ffi :as ffi]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.spec :refer [spec-for]]
   [linear.usecase.database.evaluator :as evaluator])
  (:import
   (java.lang AutoCloseable)
   (java.lang.foreign MemorySegment)
   (java.nio.charset StandardCharsets)))

(def ^:private database-configurations
  [[ffi/sqlite-dbconfig-no-checkpoint-on-close 1]
   [ffi/sqlite-dbconfig-defensive 1]
   [ffi/sqlite-dbconfig-trusted-schema 0]
   [ffi/sqlite-dbconfig-enable-load-extension 0]])

(defonce ^:private authorizer-arena (mem/shared-arena))

(defn- authorize [_context action _argument-1 _argument-2 _database _trigger]
  (if (#{ffi/sqlite-attach
         ffi/sqlite-detach
         ffi/sqlite-transaction
         ffi/sqlite-savepoint} action)
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
          (catch Exception error
            (ffi/close database)
            (throw error)))
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

(defn- install-authorizer! [database native-authorizer]
  (let [result (ffi/set-authorizer database native-authorizer mem/null)]
    (when-not (= ffi/sqlite-ok result)
      (fault "SQLite authorizer change failed"
             (merge {:reason ::authorizer-change-failed}
                    (sqlite-diagnostics database result))))))

(defn- add-suppressed! [error suppressed]
  (when suppressed
    (.addSuppressed ^Exception error ^Exception suppressed))
  error)

(defn execute
  {:malli/schema [:-> ::connection :string :nil]}
  [connection sql]
  (ensure-open! connection)
  (let [database (:handle connection)]
    (install-authorizer! database mem/null)
    (let [outcome (try
                    (let [result (ffi/exec database sql mem/null mem/null mem/null)]
                      (when-not (= ffi/sqlite-ok result)
                        (fault "SQLite statement failed"
                               (merge {:reason ::statement-failed
                                       :sql    sql}
                                      (sqlite-diagnostics database result))))
                      {:value nil})
                    (catch Exception error
                      {:error error}))
          restore-error (try
                          (install-authorizer! database authorizer)
                          nil
                          (catch Exception error
                            error))]
      (cond
        (:error outcome)
        (throw (add-suppressed! (:error outcome) restore-error))

        restore-error
        (throw restore-error))))
  nil)

(defn- incorrect! [message data]
  (throw (ex-info message (assoc data ::anomaly/category ::anomaly/incorrect))))

(defn- native-bytes [arena value-bytes]
  (let [length  (alength ^bytes value-bytes)
        segment (mem/alloc (max 1 length) arena)]
    (when (pos? length)
      (mem/write-bytes segment length value-bytes))
    segment))

(defn- sql-segment [arena sql]
  (let [sql-bytes (.getBytes ^String sql StandardCharsets/UTF_8)
        length  (alength sql-bytes)
        segment (mem/alloc (inc length) arena)]
    (when (pos? length)
      (mem/write-bytes segment length sql-bytes))
    (mem/write-byte (mem/slice segment length 1) (byte 0))
    {:length length :segment segment}))

(defn- whitespace-byte? [value]
  (contains? #{9 10 11 12 13 32} (bit-and value 0xff)))

(defn- non-whitespace-tail? [^MemorySegment sql length ^MemorySegment tail]
  (let [offset (- (.address tail) (.address sql))]
    (and (< offset length)
         (some (fn [index]
                 (not (whitespace-byte?
                        (mem/read-byte (mem/slice sql index 1)))))
               (range offset length)))))

(defn- prepare! [database arena sql]
  (let [{:keys [length segment]} (sql-segment arena sql)
        statement-output (mem/alloc (mem/size-of ::mem/pointer) arena)
        tail-output      (mem/alloc (mem/size-of ::mem/pointer) arena)
        result           (ffi/prepare-v3 database
                                         segment
                                         length
                                         0
                                         statement-output
                                         tail-output)
        statement        (mem/read-address statement-output)
        tail             (mem/read-address tail-output)]
    (when-not (= ffi/sqlite-ok result)
      (fault "SQLite statement preparation failed"
             (merge {:reason ::statement-failed
                     :sql sql}
                    (sqlite-diagnostics database result))))
    (when (mem/null? statement)
      (incorrect! "SQLite statement is empty"
                  {:reason ::empty-statement :sql sql}))
    (when (non-whitespace-tail? segment length tail)
      (ffi/finalize statement)
      (incorrect! "Multiple SQLite statements are not allowed"
                  {:reason ::multiple-statements :sql sql}))
    statement))

(defn- bind-result! [database index result]
  (when-not (= ffi/sqlite-ok result)
    (fault "SQLite parameter binding failed"
           (merge {:reason ::parameter-binding-failed
                   :index index}
                  (sqlite-diagnostics database result)))))

(defn- bind-parameter! [database statement arena index value]
  (bind-result!
    database
    index
    (cond
      (nil? value)
      (ffi/bind-null statement index)

      (instance? Long value)
      (ffi/bind-int64 statement index value)

      (instance? Double value)
      (ffi/bind-double statement index value)

      (string? value)
      (let [bytes (.getBytes ^String value StandardCharsets/UTF_8)]
        (ffi/bind-text64 statement
                         index
                         (native-bytes arena bytes)
                         (alength bytes)
                         mem/null
                         ffi/sqlite-utf8))

      (bytes? value)
      (ffi/bind-blob64 statement
                       index
                       (native-bytes arena value)
                       (alength ^bytes value)
                       mem/null)

      :else
      (incorrect! "Unsupported SQLite parameter"
                  {:reason ::unsupported-parameter
                   :index index
                   :type (type value)}))))

(defn- bind-parameters! [database statement arena parameters]
  (let [expected (ffi/bind-parameter-count statement)
        actual   (count parameters)]
    (when-not (= expected actual)
      (incorrect! "SQLite parameter count does not match"
                  {:reason ::parameter-count-mismatch
                   :expected expected
                   :actual actual})))
  (doseq [[offset value] (map-indexed vector parameters)]
    (bind-parameter! database statement arena (inc offset) value)))

(defn- step-to-completion! [database statement]
  (loop []
    (let [result (ffi/step statement)]
      (cond
        (= ffi/sqlite-done result)
        nil

        (= ffi/sqlite-row result)
        (recur)

        :else
        (fault "SQLite statement failed"
               (merge {:reason ::statement-failed}
                      (sqlite-diagnostics database result)))))))

(defn execute-statement
  {:malli/schema [:-> ::connection ::evaluator/statement :nil]}
  [connection {:keys [sql parameters]}]
  (ensure-open! connection)
  (let [database (:handle connection)]
    (with-open [arena (mem/confined-arena)]
      (let [statement (prepare! database arena sql)
            outcome   (try
                        (bind-parameters! database statement arena parameters)
                        (step-to-completion! database statement)
                        {:value nil}
                        (catch Exception error
                          {:error error}))
            finalize-result (ffi/finalize statement)
            finalize-error (when-not (= ffi/sqlite-ok finalize-result)
                             (try
                               (fault "SQLite statement finalization failed"
                                      (merge {:reason ::statement-finalization-failed}
                                             (sqlite-diagnostics database finalize-result)))
                               (catch Exception error
                                 error)))
            error      (:error outcome)]
        (cond
          error
          (throw (add-suppressed! error finalize-error))

          finalize-error
          (throw finalize-error)

          :else
          nil)))))

(defn close
  {:malli/schema [:-> ::connection :nil]}
  [connection]
  (.close ^AutoCloseable connection))
