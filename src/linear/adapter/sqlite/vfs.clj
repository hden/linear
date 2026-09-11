(ns linear.adapter.sqlite.vfs
  "Coffi-facing SQLite VFS support."
  (:require
   [coffi.ffi :as coffi]
   [coffi.mem :as mem]
   [cognitect.anomalies :as anomaly]
   [hden.ulid :refer [ulid]]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.adapter.sqlite.ffi :as ffi]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.spec :refer [spec-for]]
   [promesa.core :as p])
  (:import
   (java.lang.foreign MemorySegment)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent TimeoutException)))

(def ^:private ^:const sqlite-vfs-version 1)
(def ^:private ^:const sqlite-io-methods-version 1)
(def ^:private ^:const callback-timeout-ms 10000)

(defmethod spec-for ::resources [_]
  [:map
   [:arena :any]
   [:native-vfs :any]
   [:name :string]
   [:callbacks [:sequential ifn?]]
   [:state [:fn #(instance? clojure.lang.IAtom %)]]])

(defmethod spec-for ::options [_]
  [:map
   [:name {:optional true} :string]])

(defmethod spec-for ::invocation [_]
  [:map
   [:resources ::resources]
   [:path ::sqlite/path]
   [:filesystem ::sqlite/filesystem]
   [:failure [:fn #(instance? clojure.lang.IAtom %)]]])

(def ^:private pointer ::mem/pointer)
(def ^:private pointer-size (mem/size-of pointer))
(def ^:private int-size (mem/size-of ::mem/int))

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
   [:file-id   [::mem/array ::mem/byte 29]]])

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

(defn- write-file-id! [^MemorySegment native-file file-id]
  (let [encoded (.getBytes ^String file-id StandardCharsets/US_ASCII)]
    (when-not (= 29 (alength encoded))
      (fault "SQLite FileId has an invalid encoded length"
             {:reason  ::invalid-file-id
              :file-id file-id}))
    (mem/write-bytes (mem/slice (mem/reinterpret native-file file-size)
                                (file-offset :file-id)
                                29)
                     29
                     encoded)
    native-file))

(defn- read-file-id [^MemorySegment native-file]
  (String. ^bytes (mem/read-bytes (mem/slice (mem/reinterpret native-file file-size)
                                             (file-offset :file-id)
                                             29)
                                  29)
           StandardCharsets/US_ASCII))

(defn- callback-failure [context throwable]
  (let [data (merge {::anomaly/category ::anomaly/fault}
                    (when (instance? clojure.lang.ExceptionInfo throwable)
                      (ex-data throwable)))]
    (assoc data
           :operation (:operation context)
           :file-key (:file-key context)
           :cause throwable)))

(defn- ptr-at [^MemorySegment segment offset]
  (mem/read-address (mem/slice segment offset pointer-size)))

(defn- set-ptr! [^MemorySegment segment offset pointer-value]
  (mem/write-address (mem/slice segment offset pointer-size) pointer-value))

(defn- record-callback-failure! [resources invocation context throwable]
  (let [failure (if (instance? VirtualMachineError throwable)
                  throwable
                  (callback-failure context throwable))
        target  (or (:failure invocation) (:state resources))]
    (swap! target
           (fn [state]
             (if (:failure state)
               state
               (cond-> (assoc state :failure failure)
                 (nil? invocation) (assoc :liveness :failed)))))))

(defn- quiescent? [state]
  (and (empty? (:paths state))
       (empty? (:files state))
       (empty? (:callbacks state))))

(defn- signal-if-drained! [state]
  (when (and (= :draining (:status state))
             (quiescent? state))
    (deliver (:drained state) true)))

(defn- callback-watchdog [resources callback-id context]
  (let [completion (p/deferred)]
    (-> completion
        (p/timeout callback-timeout-ms)
        (p/catch TimeoutException
                 (fn [error]
                   (record-callback-failure! resources nil
                                             (assoc context :callback-id callback-id)
                                             error))))
    completion))

(defn- guarded-callback [resources invocation context f]
  (let [callback-id (Object.)
        completion  (callback-watchdog resources callback-id context)]
    (swap! (:state resources) assoc-in [:callbacks callback-id] context)
    (try
      (f)
      (catch Exception error
        (record-callback-failure! resources invocation context error)
        ffi/sqlite-ioerr)
      (finally
        (let [state (swap! (:state resources) update :callbacks dissoc callback-id)]
          (p/resolve completion)
          (signal-if-drained! state))))))

(defn- protect-file-callback [resources operation implementation]
  (fn [native-file & arguments]
    (try
      (let [file-key (read-file-id native-file)
            route    (get-in @(:state resources) [:files file-key])
            context  {:operation operation :file-key file-key}]
        (guarded-callback resources
                          (:invocation route)
                          context
                          #(apply implementation file-key arguments)))
      (catch Exception error
        (record-callback-failure! resources nil {:operation operation} error)
        ffi/sqlite-ioerr))))

(defn- file-route [resources file-key]
  (or (get-in @(:state resources) [:files file-key])
      (fault "SQLite VFS used an unknown file"
             {:reason ::unknown-file :file-key file-key})))

(defn- file-for [resources file-key]
  (:file (file-route resources file-key)))

(defn- invocation-for-path [resources path]
  (get-in @(:state resources) [:paths path]))

(defn- write-c-string! [^MemorySegment output capacity value]
  (let [path-bytes (.getBytes ^String value StandardCharsets/UTF_8)
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

(def ^:private open-kinds
  [[ffi/sqlite-open-main-db :main-db]
   [ffi/sqlite-open-temp-db :temp-db]
   [ffi/sqlite-open-transient-db :transient-db]
   [ffi/sqlite-open-main-journal :main-journal]
   [ffi/sqlite-open-temp-journal :temp-journal]
   [ffi/sqlite-open-subjournal :subjournal]
   [ffi/sqlite-open-super-journal :super-journal]
   [ffi/sqlite-open-wal :wal]])

(def ^:private open-options
  [[ffi/sqlite-open-create :create]
   [ffi/sqlite-open-delete-on-close :delete-on-close]
   [ffi/sqlite-open-exclusive :exclusive]
   [ffi/sqlite-open-auto-proxy :auto-proxy]
   [ffi/sqlite-open-uri :uri]
   [ffi/sqlite-open-memory :memory]
   [ffi/sqlite-open-no-mutex :no-mutex]
   [ffi/sqlite-open-full-mutex :full-mutex]
   [ffi/sqlite-open-shared-cache :shared-cache]
   [ffi/sqlite-open-private-cache :private-cache]
   [ffi/sqlite-open-no-follow :no-follow]
   [ffi/sqlite-open-extended-result-codes :extended-result-codes]])

(defn- flag-set? [flags mask]
  (= mask (bit-and flags mask)))

(defn- decode-open-request [path flags]
  {:path path
   :requested-mode (if (flag-set? flags ffi/sqlite-open-readwrite)
                     :read-write
                     :read-only)
   :kind (or (some (fn [[mask kind]]
                     (when (flag-set? flags mask) kind))
                   open-kinds)
             :main-db)
   :options (into #{}
                  (keep (fn [[mask option]]
                          (when (flag-set? flags mask) option)))
                  open-options)})

(defn- encode-open-result [requested-flags mode]
  (-> requested-flags
      (bit-and-not ffi/sqlite-open-readonly ffi/sqlite-open-readwrite)
      (bit-or (case mode
                :read-only ffi/sqlite-open-readonly
                :read-write ffi/sqlite-open-readwrite))))

(defn- decode-access-mode [flags]
  (condp = (int flags)
    ffi/sqlite-access-exists :exists
    ffi/sqlite-access-readwrite :read-write
    ffi/sqlite-access-read :read
    (fault "SQLite VFS received an unknown access mode"
           {:reason ::unknown-access-mode :flags flags})))

(defn- io-callbacks [resources io-methods arena keepalive]
  (let [register! (fn [name argument-types implementation]
                    (let [protected (protect-file-callback resources name implementation)
                          pointer   (mem/serialize protected
                                                   [::coffi/fn argument-types ::mem/int]
                                                   arena)]
                      (swap! keepalive conj protected)
                      pointer))]
    (doseq [[name argument-types implementation]
            [[:x-close [pointer]
              (fn [file-key]
                (let [file (file-for resources file-key)]
                  (sqlite/close file)
                  (swap! (:state resources) update :files dissoc file-key))
                ffi/sqlite-ok)]
             [:x-read [pointer pointer ::mem/int ::mem/long]
              (fn [file-key buffer amount offset]
                (let [amount (int amount)
                      result (sqlite/read (file-for resources file-key) offset amount)
                      ^bytes bytes (:bytes result)
                      length (alength bytes)
                      output (mem/reinterpret buffer amount)
                      short-read? (or (:short-read? result) (< length amount))]
                  (when (> length amount)
                    (fault "SQLite File returned too many bytes"
                           {:reason ::oversized-read
                            :requested amount
                            :actual length}))
                  (when short-read?
                    (.fill ^MemorySegment output (byte 0)))
                  (mem/copy-segment (mem/slice output 0 length)
                                    (MemorySegment/ofArray bytes))
                  (if short-read?
                    ffi/sqlite-ioerr-short-read
                    ffi/sqlite-ok)))]
             [:x-write [pointer pointer ::mem/int ::mem/long]
              (fn [file-key buffer amount offset]
                (let [amount (int amount)]
                  (sqlite/write (file-for resources file-key)
                                offset
                                (read-native-bytes buffer amount))
                  ffi/sqlite-ok))]
             [:x-truncate [pointer ::mem/long]
              (fn [file-key size]
                (sqlite/truncate (file-for resources file-key) size)
                ffi/sqlite-ok)]
             [:x-sync [pointer ::mem/int]
              (fn [file-key _flags]
                (sqlite/sync (file-for resources file-key))
                ffi/sqlite-ok)]
             [:x-file-size [pointer pointer]
              (fn [file-key output]
                (let [size (sqlite/size (file-for resources file-key))]
                  (mem/write-long (mem/reinterpret output 8) size)
                  ffi/sqlite-ok))]
             [:x-lock [pointer ::mem/int] (fn [_file-key _lock] ffi/sqlite-ok)]
             [:x-unlock [pointer ::mem/int] (fn [_file-key _lock] ffi/sqlite-ok)]
             [:x-check-reserved-lock [pointer pointer]
              (fn [_file-key output]
                (mem/write-int (mem/reinterpret output 4) 0)
                ffi/sqlite-ok)]
             [:x-file-control [pointer ::mem/int pointer]
              (fn [_file-key _operation _argument] ffi/sqlite-notfound)]
             [:x-sector-size [pointer] (fn [_file-key] 4096)]
             [:x-device-characteristics [pointer] (fn [_file-key] 0)]]]
      (set-ptr! io-methods
                (io-method-offset name)
                (register! name argument-types implementation)))))

(defn- vfs-callback
  [resources arena keepalive name argument-types invocation-fn implementation]
  (let [protected (fn [& arguments]
                    (guarded-callback resources
                                      (apply invocation-fn arguments)
                                      {:operation name}
                                      #(apply implementation arguments)))
        pointer   (mem/serialize protected [::coffi/fn argument-types ::mem/int] arena)]
    (swap! keepalive conj protected)
    pointer))

(defn- install-vfs-callbacks! [resources native-vfs io-methods arena keepalive]
  (set-ptr! native-vfs
            (vfs-offset :x-open)
            (vfs-callback resources
                          arena
                          keepalive
                          :x-open
                          [pointer ::mem/c-string pointer ::mem/int pointer]
                          (fn [_native-vfs path & _]
                            (invocation-for-path resources path))
                          (fn [_native-vfs path native-file requested-flags output-flags]
                            (let [native-file (mem/reinterpret native-file file-size)]
                              (set-ptr! native-file (file-offset :p-methods) mem/null)
                              (let [invocation (invocation-for-path resources path)
                                    {file :file actual-mode :mode}
                                    (sqlite/open (:filesystem invocation)
                                                 (decode-open-request path requested-flags))
                                    file-key    (str "vf-" (ulid))
                                    actual-flags (encode-open-result requested-flags actual-mode)]
                                (swap! (:state resources) assoc-in [:files file-key]
                                       {:file file :invocation invocation})
                                (set-ptr! native-file (file-offset :p-methods) io-methods)
                                (write-file-id! native-file file-key)
                                (when-not (mem/null? output-flags)
                                  (mem/write-int (mem/reinterpret output-flags 4) actual-flags))
                                ffi/sqlite-ok)))))
  (set-ptr! native-vfs
            (vfs-offset :x-delete)
            (vfs-callback resources
                          arena
                          keepalive
                          :x-delete
                          [pointer ::mem/c-string ::mem/int]
                          (fn [_native-vfs path & _]
                            (invocation-for-path resources path))
                          (fn [_native-vfs path _sync-directory]
                            (sqlite/delete (:filesystem (invocation-for-path resources path))
                                           {:path path})
                            ffi/sqlite-ok)))
  (set-ptr! native-vfs
            (vfs-offset :x-access)
            (vfs-callback resources
                          arena
                          keepalive
                          :x-access
                          [pointer ::mem/c-string ::mem/int pointer]
                          (fn [_native-vfs path & _]
                            (invocation-for-path resources path))
                          (fn [_native-vfs path flags output]
                            (let [filesystem (:filesystem (invocation-for-path resources path))
                                  accessible? (and filesystem
                                                   (sqlite/access filesystem
                                                                  {:path path
                                                                   :mode (decode-access-mode flags)}))]
                              (mem/write-int (mem/reinterpret output 4) (if accessible? 1 0))
                              ffi/sqlite-ok))))
  (set-ptr! native-vfs
            (vfs-offset :x-full-pathname)
            (vfs-callback resources
                          arena
                          keepalive
                          :x-full-pathname
                          [pointer ::mem/c-string ::mem/int pointer]
                          (fn [_native-vfs path & _]
                            (invocation-for-path resources path))
                          (fn [_native-vfs path capacity output]
                            (let [filesystem (:filesystem (invocation-for-path resources path))]
                              (write-c-string! output capacity
                                               (if filesystem
                                                 (sqlite/full-path filesystem {:path path})
                                                 path)))
                            ffi/sqlite-ok))))

(defn install
  {:malli/schema [:-> ::options ::resources]}
  [{:keys [name]
    :or {name (str "rt-" (ulid))}}]
  (ffi/load!)
  (let [arena       (mem/shared-arena)
        parent      (ffi/vfs-find mem/null)
        parent-vfs  (mem/as-segment (mem/address-of parent) vfs-size)
        native-vfs  (mem/alloc vfs-size arena)
        io-methods  (mem/alloc io-methods-size arena)
        keepalive   (atom [])
        resources   {:arena      arena
                     :native-vfs native-vfs
                     :name       name
                     :state      (atom {:status :ready
                                        :liveness :ok
                                        :paths {}
                                        :files {}
                                        :callbacks {}
                                        :failure nil
                                        :drained (promise)})}]
    (try
      (mem/write-int (mem/slice io-methods
                                (io-method-offset :i-version)
                                int-size)
                     sqlite-io-methods-version)
      (io-callbacks resources io-methods arena keepalive)
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
      (install-vfs-callbacks! resources native-vfs io-methods arena keepalive)
      (let [resources (assoc resources :callbacks @keepalive)
            result    (ffi/vfs-register native-vfs)]
        (if (zero? result)
          resources
          (fault "SQLite VFS registration failed"
                 {:reason ::vfs-registration-failed
                  :code   result})))
      (catch Exception error
        (.close arena)
        (throw error)))))

(defn- mounted-paths [path]
  [path (str path "-wal") (str path "-shm")])

(defn mount
  {:malli/schema [:->
                  ::resources
                  [:map
                   [:path ::sqlite/path]
                   [:filesystem ::sqlite/filesystem]]
                  ::invocation]}
  [resources {:keys [path filesystem]}]
  (let [invocation {:resources resources
                    :path path
                    :filesystem filesystem
                    :failure (atom {:failure nil})}
        paths      (mounted-paths path)]
    (swap! (:state resources)
           (fn [state]
             (when-not (= :ready (:status state))
               (fault "SQLite VFS is unavailable"
                      {:reason ::unavailable :status (:status state)}))
             (when (some (:paths state) paths)
               (fault "SQLite VFS path is already open"
                      {:reason ::duplicate-path :path path}))
             (update state :paths #(reduce (fn [result value]
                                             (assoc result value invocation))
                                           %
                                           paths))))
    invocation))

(defn throw-if-failed!
  {:malli/schema [:-> ::invocation :nil]}
  [{:keys [resources failure]}]
  (when-let [failure (or (:failure @failure)
                         (:failure @(:state resources)))]
    (if (instance? VirtualMachineError failure)
      (throw failure)
      (throw (ex-info "SQLite callback failed" failure (:cause failure)))))
  nil)

(defn unmount
  {:malli/schema [:-> ::invocation :nil]}
  [{:keys [resources path] :as invocation}]
  (let [paths (mounted-paths path)
        state (swap! (:state resources)
                (fn [state]
                  (-> state
                      (update :paths #(apply dissoc % paths))
                      (update :files (fn [files]
                                       (into {}
                                             (remove (fn [[_ route]]
                                                       (identical? invocation (:invocation route))))
                                             files))))))]
    (signal-if-drained! state))
  nil)

(defn ok?
  {:malli/schema [:-> ::resources :boolean]}
  [resources]
  (= :ok (:liveness @(:state resources))))

(defn drain!
  {:malli/schema [:-> ::resources :nil]}
  [resources]
  (let [state (swap! (:state resources)
                (fn [state]
                  (case (:status state)
                    :ready (assoc state :status :draining)
                    :draining state
                    state)))]
    (signal-if-drained! state))
  nil)

(defn uninstall
  {:malli/schema [:-> ::resources :nil]}
  [resources]
  (let [{:keys [paths files callbacks]} @(:state resources)]
    (when (or (seq paths) (seq files) (seq callbacks))
      (fault "SQLite VFS still has active resources"
             {:reason ::resources-active
              :paths (count paths)
              :files (count files)
              :callbacks callbacks})))
  (let [result (ffi/vfs-unregister (:native-vfs resources))]
    (if (zero? result)
      (.close ^java.lang.AutoCloseable (:arena resources))
      (fault "SQLite VFS unregistration failed"
             {:reason ::vfs-unregistration-failed
              :code   result})))
  nil)
