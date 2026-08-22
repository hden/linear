(ns linear.adapter.pagestore.impl.sqlite.driver
  "Coffi-facing SQLite VFS support."
  (:require
   [coffi.ffi :as ffi]
   [coffi.mem :as mem]
   [cognitect.anomalies :as anomaly]
   [integrant.core :as integrant]
   [linear.adapter.pagestore.impl.sqlite.session :as session]
   [linear.adapter.pagestore.impl.sqlite.vfs :as vfs])
  (:import
   (java.lang.foreign FunctionDescriptor Linker Linker$Option MemoryLayout MemorySegment)
   (java.lang.invoke MethodHandle)))

(def ^:const sqlite-ok 0)
(def ^:const sqlite-ioerr 10)
(def ^:const sqlite-ioerr-short-read 522)
(def ^:const sqlite-readonly 8)
(def ^:const sqlite-notfound 12)
(def ^:const sqlite-auth 23)
(def ^:const sqlite-open-privatecache 0x00040000)
(def ^:const sqlite-open-readwrite (bit-or 0x00000002 sqlite-open-privatecache))

(def ^:private ^:const sqlite-deny 1)
(def ^:private ^:const sqlite-attach 24)
(def ^:private ^:const sqlite-detach 25)
(def ^:private ^:const sqlite-dbconfig-enable-load-extension 1005)
(def ^:private ^:const sqlite-dbconfig-defensive 1010)
(def ^:private ^:const sqlite-dbconfig-trusted-schema 1017)
(def ^:private ^:const sqlite-vfs-version 1)
(def ^:private ^:const sqlite-io-methods-version 1)

(def ^:private database-security-configurations
  [[sqlite-dbconfig-defensive 1]
   [sqlite-dbconfig-trusted-schema 0]
   [sqlite-dbconfig-enable-load-extension 0]])

(def ^:private pointer ::mem/pointer)
(def ^:private pointer-size (mem/size-of pointer))
(def ^:private int-size (mem/size-of ::mem/int))
(def ^:private long-size (mem/size-of ::mem/long))

(defn- align-up [offset alignment]
  (+ offset (mod (- alignment (mod offset alignment)) alignment)))

(defn- c-struct-layout [fields]
  (let [{:keys [offsets size alignment]}
        (reduce (fn [{:keys [offsets size alignment]} [field type]]
                  (let [field-alignment (mem/align-of type)
                        field-offset    (align-up size field-alignment)]
                    {:offsets   (assoc offsets field field-offset)
                     :size      (+ field-offset (mem/size-of type))
                     :alignment (max alignment field-alignment)}))
                {:offsets {} :size 0 :alignment 1}
                fields)]
    {:offsets offsets
     :size    (align-up size alignment)}))

(def ^:private sqlite3-vfs-v1-fields
  [[:i-version        ::mem/int]
   [:sz-os-file       ::mem/int]
   [:mx-pathname      ::mem/int]
   [:p-next           pointer]
   [:z-name           pointer]
   [:p-app-data       pointer]
   [:x-open           pointer]
   [:x-delete         pointer]
   [:x-access         pointer]
   [:x-full-pathname  pointer]
   [:x-dl-open        pointer]
   [:x-dl-error       pointer]
   [:x-dl-sym         pointer]
   [:x-dl-close       pointer]
   [:x-randomness     pointer]
   [:x-sleep          pointer]
   [:x-current-time   pointer]
   [:x-get-last-error pointer]])

(def ^:private sqlite3-vfs-v1-callback-fields
  (drop-while #(not= :x-open (first %)) sqlite3-vfs-v1-fields))

(def ^:private sqlite3-io-methods-v1-fields
  [[:i-version                 ::mem/int]
   [:x-close                   pointer]
   [:x-read                    pointer]
   [:x-write                   pointer]
   [:x-truncate                pointer]
   [:x-sync                    pointer]
   [:x-file-size               pointer]
   [:x-lock                    pointer]
   [:x-unlock                  pointer]
   [:x-check-reserved-lock     pointer]
   [:x-file-control            pointer]
   [:x-sector-size             pointer]
   [:x-device-characteristics pointer]])

(def ^:private sqlite3-file-fields
  [[:p-methods pointer]
   [:key       ::mem/long]])

(def ^:private sqlite3-vfs-v1-layout (c-struct-layout sqlite3-vfs-v1-fields))
(def ^:private sqlite3-io-methods-v1-layout (c-struct-layout sqlite3-io-methods-v1-fields))
(def ^:private sqlite3-file-layout (c-struct-layout sqlite3-file-fields))

(defn- field-offset [layout field]
  (get-in layout [:offsets field]))

(def ^:private vfs-size (:size sqlite3-vfs-v1-layout))
(def ^:private io-methods-size (:size sqlite3-io-methods-v1-layout))
(def ^:private file-size (:size sqlite3-file-layout))

(defn- vfs-offset [field]
  (field-offset sqlite3-vfs-v1-layout field))

(defn- io-method-offset [field]
  (field-offset sqlite3-io-methods-v1-layout field))

(defn- file-offset [field]
  (field-offset sqlite3-file-layout field))

(declare close-database! fault)

(defn new-failure-slot []
  (atom nil))

(defn- callback-failure [context throwable]
  (let [data (merge {::anomaly/category ::anomaly/fault}
                    (when (instance? clojure.lang.ExceptionInfo throwable)
                      (ex-data throwable)))]
    (assoc data
           :operation (:operation context)
           :file (:file context)
           :cause throwable)))

(defn guarded-upcall [failure context f]
  (try
    (f)
    (catch Throwable throwable
      ;; SQLite cannot safely unwind a Clojure Throwable through a C callback.
      ;; A VirtualMachineError must reach the Java caller unchanged. Avoid
      ;; allocating a failure map while the VM may be out of memory.
      (compare-and-set! failure
                        nil
                        (if (instance? VirtualMachineError throwable)
                          throwable
                          (callback-failure context throwable)))
      sqlite-ioerr)))

(defn- unexpected-main-database-mutation! [sqlite-session operation]
  (let [cause (ex-info "SQLite attempted to mutate the virtual main database"
                       {::anomaly/category ::anomaly/fault
                        :reason            ::unexpected-main-database-mutation
                        :operation         operation
                        :file              :main-db})]
    (compare-and-set! (session/failure-slot sqlite-session)
                      nil
                      (callback-failure {:operation operation
                                         :file      :main-db}
                                        cause))))

(defn- main-database-mutation-result [sqlite-session operation]
  (if (session/closing? sqlite-session)
    ;; SQLite may checkpoint or clean up its WAL while closing. The Page Store
    ;; already owns the committed Revision, so that write is intentionally a no-op.
    sqlite-ok
    (do
      (unexpected-main-database-mutation! sqlite-session operation)
      sqlite-readonly)))

(defn- library-load-error [library throwable]
  (let [data {::anomaly/category ::anomaly/fault
              :reason            ::sqlite-library-load-failed
              :library           library}]
    (ex-info "Unable to load SQLite native library"
             (if (instance? clojure.lang.ExceptionInfo throwable)
               (assoc data :external-data (ex-data throwable))
               data)
             throwable)))

(defonce ^:private native-api (atom nil))

(defn- sqlite3-db-config-handle []
  (let [descriptor (FunctionDescriptor/of
                     (mem/c-layout ::mem/int)
                     (into-array MemoryLayout [(mem/c-layout pointer)
                                               (mem/c-layout ::mem/int)
                                               (mem/c-layout ::mem/int)
                                               (mem/c-layout pointer)]))]
    (.downcallHandle (Linker/nativeLinker)
                     (ffi/find-symbol "sqlite3_db_config")
                     descriptor
                     (into-array Linker$Option [(Linker$Option/firstVariadicArg 2)]))))

(defn- create-native-api []
  {:libversion             (ffi/cfn "sqlite3_libversion" [] ::mem/c-string)
   :db-config              (sqlite3-db-config-handle)
   :enable-load-extension  (ffi/cfn "sqlite3_enable_load_extension"
                             [pointer ::mem/int]
                             ::mem/int)
   :set-authorizer         (ffi/cfn "sqlite3_set_authorizer" [pointer pointer pointer] ::mem/int)
   :open-v2                (ffi/cfn "sqlite3_open_v2"
                             [::mem/c-string pointer ::mem/int ::mem/c-string]
                             ::mem/int)
   :close                  (ffi/cfn "sqlite3_close" [pointer] ::mem/int)
   :exec                   (ffi/cfn "sqlite3_exec"
                             [pointer ::mem/c-string pointer pointer pointer]
                             ::mem/int)
   :vfs-find               (ffi/cfn "sqlite3_vfs_find" [pointer] pointer)
   :vfs-register           (ffi/cfn "sqlite3_vfs_register" [pointer ::mem/int] ::mem/int)
   :vfs-unregister         (ffi/cfn "sqlite3_vfs_unregister" [pointer] ::mem/int)})

(defn load-sqlite! [library]
  (try
    (ffi/load-library library)
    (or @native-api
        (reset! native-api (create-native-api)))
    (catch VirtualMachineError throwable
      (throw throwable))
    (catch clojure.lang.ExceptionInfo throwable
      (throw (library-load-error library throwable)))
    (catch Throwable throwable
      (throw (library-load-error library throwable)))))

(defn- sqlite3-libversion []
  ((:libversion @native-api)))

(defn- sqlite3-enable-load-extension [database enabled?]
  ((:enable-load-extension @native-api) database enabled?))

(defn- sqlite3-set-authorizer [database authorizer context]
  ((:set-authorizer @native-api) database authorizer context))

(defn- sqlite3-open-v2 [path output flags vfs-name]
  ((:open-v2 @native-api) path output flags vfs-name))

(defn- sqlite3-close [database]
  ((:close @native-api) database))

(defn- sqlite3-exec [database sql callback callback-context error-message]
  ((:exec @native-api) database sql callback callback-context error-message))

(defn sqlite-version []
  (sqlite3-libversion))

(defonce ^:private authorizer-arena (mem/shared-arena))

(defn- authorize [_context action _argument-1 _argument-2 _database _trigger]
  (if (#{sqlite-attach sqlite-detach} action)
    sqlite-deny
    sqlite-ok))

(defonce ^:private authorizer
  (mem/serialize authorize
                 [::ffi/fn [pointer
                            ::mem/int
                            ::mem/c-string
                            ::mem/c-string
                            ::mem/c-string
                            ::mem/c-string]
                  ::mem/int]
                 authorizer-arena))

(defn- sqlite3-db-config [database option value]
  (let [^MethodHandle handle (:db-config @native-api)]
    (.intValue ^Integer (.invokeWithArguments handle
                          (object-array [database
                                         (int option)
                                         (int value)
                                         mem/null])))))

(defn- configure-database! [database]
  (doseq [[option value] database-security-configurations]
    (let [result (sqlite3-db-config database option value)]
      (when-not (zero? result)
        (fault "SQLite security configuration failed"
               {:reason ::sqlite-security-configuration-failed
                :option option
                :code   result}))))
  (let [result (sqlite3-enable-load-extension database 0)]
    (when-not (zero? result)
      (fault "SQLite extension loading disable failed"
             {:reason ::sqlite-security-configuration-failed
              :option :extension-loading
              :code   result})))
  (let [result (sqlite3-set-authorizer database authorizer mem/null)]
    (when-not (zero? result)
      (fault "SQLite ATTACH authorizer installation failed"
             {:reason ::sqlite-security-configuration-failed
              :option :attach
              :code   result}))))

(defn open-database! [path flags vfs-name]
  (with-open [arena (mem/confined-arena)]
    (let [output (mem/alloc pointer-size arena)
          result (sqlite3-open-v2 path output flags vfs-name)
          database (mem/read-address output)]
      (if (zero? result)
        (try
          (configure-database! database)
          database
          (catch Throwable throwable
            (close-database! database)
            (throw throwable)))
        (do
          ;; SQLite may allocate a closeable handle even when opening fails.
          (when-not (mem/null? database)
            (sqlite3-close database))
          (fault "SQLite database open failed"
                 {:reason ::sqlite-open-failed
                  :code   result
                  :path   path}))))))

(defn execute-sql! [database sql]
  (sqlite3-exec database sql mem/null mem/null mem/null))

(defn close-database! [database]
  (sqlite3-close database))

(defn- ptr-at [^MemorySegment segment offset]
  (mem/read-address (mem/slice segment offset pointer-size)))

(defn- set-ptr! [^MemorySegment segment offset pointer-value]
  (mem/write-address (mem/slice segment offset pointer-size) pointer-value))

(defn- sqlite3-vfs-find [name]
  ((:vfs-find @native-api) name))

(defn- sqlite3-vfs-register [native-vfs]
  ((:vfs-register @native-api) native-vfs 0))

(defn- sqlite3-vfs-unregister [native-vfs]
  ((:vfs-unregister @native-api) native-vfs))

(defn- fault [message data]
  (throw (ex-info message (assoc data ::anomaly/category ::anomaly/fault))))

(defn- next-file-key! [runtime]
  (swap! (:next-file-key runtime) inc))

(defn- native-file-key [^MemorySegment native-file]
  (mem/read-long (mem/slice (mem/reinterpret native-file file-size)
                            (file-offset :key)
                            long-size)))

(defn- file-state [runtime ^MemorySegment native-file]
  (let [key (native-file-key native-file)]
    (or (get @(:files runtime) key)
        (fault "SQLite VFS callback used an unknown file handle"
               {:reason ::unknown-file-handle
                :key    key}))))

(defn- callback-slot [runtime native-file]
  (if native-file
    (some-> (file-state runtime native-file) :session session/failure-slot)
    (:failure runtime)))

(defn- guarded-callback [runtime native-file context f]
  (try
    (let [context (assoc context
                         :file (if native-file
                                 (:kind (file-state runtime native-file))
                                 :vfs))]
      (guarded-upcall (callback-slot runtime native-file) context f))
    (catch Throwable throwable
      (compare-and-set! (:failure runtime)
                        nil
                        (if (instance? VirtualMachineError throwable)
                          throwable
                          (callback-failure context throwable)))
      sqlite-ioerr)))

(defn- file-for-path [runtime path]
  (or (when-let [session (get @(:sessions runtime) path)]
        {:kind :main-db
         :session session})
      (some (fn [[main-path session]]
              (when (= path (str main-path "-wal"))
                {:kind :wal
                 :session session}))
            @(:sessions runtime))))

(defn- write-c-string! [^MemorySegment output capacity value]
  (let [path-bytes (.getBytes ^String value "UTF-8")
        length     (alength path-bytes)]
    (when (<= capacity length)
      (fault "SQLite VFS path buffer is too small"
             {:reason   ::path-buffer-too-small
              :capacity capacity
              :length   length}))
    (let [output (mem/reinterpret output capacity)]
      (mem/copy-segment (mem/slice output 0 length) (MemorySegment/ofArray path-bytes))
      (mem/write-byte (mem/slice output length 1) (byte 0)))))

(defn- read-native-bytes [^MemorySegment input length]
  (let [output (byte-array length)]
    (mem/copy-segment (MemorySegment/ofArray output) (mem/reinterpret input length))
    output))

(defn- io-callbacks [runtime io-methods arena keepalive]
  (let [register! (fn [name argument-types implementation]
                    (let [protected (fn [& arguments]
                                      (let [native-file (first arguments)]
                                        (guarded-callback runtime
                                                          native-file
                                                          {:operation name}
                                                          #(apply implementation arguments))))
                          pointer   (mem/serialize protected
                                                   [::ffi/fn argument-types ::mem/int]
                                                   arena)]
                      (swap! keepalive conj protected)
                      pointer))]
    (doseq [[name argument-types implementation]
            [[:x-close [pointer]
              (fn [native-file]
                (let [key (native-file-key native-file)]
                  (swap! (:files runtime) dissoc key)
                  sqlite-ok))]
             [:x-read [pointer pointer ::mem/int ::mem/long]
              (fn [native-file buffer amount offset]
                (let [amount (int amount)
                      {:keys [kind session]} (file-state runtime native-file)
                      result (case kind
                               :main-db (vfs/read-main-database (session/database session)
                                          offset
                                          amount)
                               :wal (session/read-wal session offset amount))]
                  (let [^bytes bytes (:bytes result)]
                    (mem/copy-segment (mem/reinterpret buffer amount)
                                      (MemorySegment/ofArray bytes)))
                  (if (:short-read? result)
                    sqlite-ioerr-short-read
                    sqlite-ok)))]
             [:x-write [pointer pointer ::mem/int ::mem/long]
              (fn [native-file buffer amount offset]
                (let [amount (int amount)
                      {:keys [kind session]} (file-state runtime native-file)]
                  (if (= kind :wal)
                    (do
                      (session/write-owned-wal! session
                                                offset
                                                (read-native-bytes buffer amount))
                      sqlite-ok)
                    (main-database-mutation-result session :x-write))))]
             [:x-truncate [pointer ::mem/long]
              (fn [native-file size]
                (let [{:keys [kind session]} (file-state runtime native-file)]
                  (if (= kind :wal)
                    (do (session/truncate-wal! session size) sqlite-ok)
                    (main-database-mutation-result session :x-truncate))))]
             [:x-sync [pointer ::mem/int]
              (fn [native-file _flags]
                (let [{:keys [kind session]} (file-state runtime native-file)]
                  (when (= kind :wal)
                    (session/sync-wal! session))
                  sqlite-ok))]
             [:x-file-size [pointer pointer]
              (fn [native-file output]
                (let [{:keys [kind session]} (file-state runtime native-file)
                      size (case kind
                             :main-db (vfs/main-database-size (session/database session))
                             :wal (session/wal-size session))]
                  (mem/write-long (mem/reinterpret output 8) size)
                  sqlite-ok))]
             [:x-lock [pointer ::mem/int] (fn [_native-file _lock] sqlite-ok)]
             [:x-unlock [pointer ::mem/int] (fn [_native-file _lock] sqlite-ok)]
             [:x-check-reserved-lock [pointer pointer]
              (fn [_native-file output]
                (mem/write-int (mem/reinterpret output 4) 0)
                sqlite-ok)]
             [:x-file-control [pointer ::mem/int pointer]
              (fn [_native-file _operation _argument] sqlite-notfound)]
             [:x-sector-size [pointer] (fn [_native-file] 4096)]
             [:x-device-characteristics [pointer] (fn [_native-file] 0)]]]
      (set-ptr! io-methods
                (io-method-offset name)
                (register! name argument-types implementation)))))

(defn- vfs-callback [runtime arena keepalive name argument-types implementation]
  (let [protected (fn [& arguments]
                    (guarded-callback runtime
                                      nil
                                      {:operation name}
                                      #(apply implementation arguments)))
        pointer   (mem/serialize protected [::ffi/fn argument-types ::mem/int] arena)]
    (swap! keepalive conj protected)
    pointer))

(defn- install-vfs-callbacks! [runtime native-vfs io-methods arena keepalive]
  (set-ptr! native-vfs
            (vfs-offset :x-open)
            (vfs-callback runtime
                          arena
                          keepalive
                          :x-open
                          [pointer ::mem/c-string pointer ::mem/int pointer]
                          (fn [_native-vfs path native-file _flags output-flags]
                            (if-let [{:keys [kind session]} (file-for-path runtime path)]
                              (let [key  (next-file-key! runtime)
                                    file (mem/reinterpret native-file file-size)]
                                (swap! (:files runtime) assoc key {:kind    kind
                                                                   :session session})
                                (mem/write-address (mem/slice file
                                                     (file-offset :p-methods)
                                                     pointer-size)
                                                   io-methods)
                                (mem/write-long (mem/slice file
                                                  (file-offset :key)
                                                  (mem/size-of ::mem/long))
                                                key)
                                (when-not (mem/null? output-flags)
                                  (mem/write-int (mem/reinterpret output-flags 4) 0))
                                sqlite-ok)
                              (fault "SQLite VFS received an unregistered file path"
                                     {:reason ::unregistered-file-path
                                      :path   path})))))
  (set-ptr! native-vfs
            (vfs-offset :x-delete)
            (vfs-callback runtime
                          arena
                          keepalive
                          :x-delete
                          [pointer ::mem/c-string ::mem/int]
                          (fn [_native-vfs path _sync-directory]
                            (when-let [{:keys [kind session]} (file-for-path runtime path)]
                              (when (= :wal kind)
                                (session/truncate-wal! session 0)))
                            sqlite-ok)))
  (set-ptr! native-vfs
            (vfs-offset :x-access)
            (vfs-callback runtime
                          arena
                          keepalive
                          :x-access
                          [pointer ::mem/c-string ::mem/int pointer]
                          (fn [_native-vfs path _flags output]
                            (let [exists? (when-let [{:keys [kind session]} (file-for-path runtime path)]
                                            (case kind
                                              :main-db (pos? (vfs/main-database-size
                                                               (session/database session)))
                                              :wal (pos? (session/wal-size session))))]
                              (mem/write-int (mem/reinterpret output 4) (if exists? 1 0))
                              sqlite-ok))))
  (set-ptr! native-vfs
            (vfs-offset :x-full-pathname)
            (vfs-callback runtime
                          arena
                          keepalive
                          :x-full-pathname
                          [pointer ::mem/c-string ::mem/int pointer]
                          (fn [_native-vfs path capacity output]
                            (write-c-string! output capacity path)
                            sqlite-ok))))

(defn install-vfs! [{:keys [library name]}]
  (load-sqlite! library)
  (let [arena       (mem/shared-arena)
        parent      (sqlite3-vfs-find mem/null)
        parent-vfs  (mem/as-segment (mem/address-of parent) vfs-size)
        native-vfs  (mem/alloc vfs-size arena)
        io-methods  (mem/alloc io-methods-size arena)
        keepalive   (atom [])
        runtime     {:arena         arena
                     :native-vfs    native-vfs
                     :name          name
                     :sessions      (atom {})
                     :files         (atom {})
                     :next-file-key (atom 0)
                     :failure       (new-failure-slot)}]
    (try
      (mem/write-int (mem/slice io-methods
                                (io-method-offset :i-version)
                                int-size)
                     sqlite-io-methods-version)
      (io-callbacks runtime io-methods arena keepalive)
      (mem/write-int (mem/slice native-vfs
                                (vfs-offset :i-version)
                                int-size)
                     sqlite-vfs-version)
      (mem/write-int (mem/slice native-vfs
                                (vfs-offset :sz-os-file)
                                int-size)
                     file-size)
      (mem/write-int (mem/slice native-vfs
                                (vfs-offset :mx-pathname)
                                int-size)
                     (mem/read-int (mem/slice parent-vfs
                                              (vfs-offset :mx-pathname)
                                              int-size)))
      (set-ptr! native-vfs (vfs-offset :p-next) mem/null)
      (set-ptr! native-vfs (vfs-offset :z-name) (mem/serialize name ::mem/c-string arena))
      (set-ptr! native-vfs (vfs-offset :p-app-data) parent)
      (doseq [[field _] sqlite3-vfs-v1-callback-fields]
        (set-ptr! native-vfs
                  (vfs-offset field)
                  (ptr-at parent-vfs (vfs-offset field))))
      (install-vfs-callbacks! runtime native-vfs io-methods arena keepalive)
      (let [runtime (assoc runtime :callbacks @keepalive)
            result  (sqlite3-vfs-register native-vfs)]
        (if (zero? result)
          runtime
          (fault "SQLite VFS registration failed"
                 {:reason ::vfs-registration-failed
                  :code   result})))
      (catch Throwable throwable
        (.close arena)
        (throw throwable)))))

(defn register-session! [runtime path session]
  (swap! (:sessions runtime) assoc path (assoc session :path path))
  runtime)

(defn unregister-session! [runtime path]
  (swap! (:sessions runtime) dissoc path)
  runtime)

(defn- ensure-no-active-sessions! [runtime]
  (let [paths (vec (sort (keys @(:sessions runtime))))]
    (when (seq paths)
      (fault "SQLite VFS shutdown requires no active sessions"
             {:reason ::active-sessions
              :paths  paths}))))

(defn close-vfs! [runtime]
  (ensure-no-active-sessions! runtime)
  (let [result (sqlite3-vfs-unregister (:native-vfs runtime))]
    (if (zero? result)
      (.close ^java.lang.AutoCloseable (:arena runtime))
      (fault "SQLite VFS unregistration failed"
             {:reason ::vfs-unregistration-failed
              :code   result}))))

(defmethod integrant/init-key :linear.adapter.pagestore.impl.sqlite/vfs [_ options]
  (install-vfs! options))

(defmethod integrant/halt-key! :linear.adapter.pagestore.impl.sqlite/vfs [_ runtime]
  (close-vfs! runtime))
