(ns linear.adapter.sqlite.ffi
  "Raw Coffi bindings for the SQLite C API."
  (:require
   [coffi.ffi :as coffi]
   [coffi.mem :as mem]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.spec :refer [spec-for]])
  (:import
   (java.lang.foreign FunctionDescriptor Linker Linker$Option MemoryLayout MemorySegment)
   (java.lang.invoke MethodHandle)))

(def ^:const sqlite-ok 0)
(def ^:const sqlite-ioerr 10)
(def ^:const sqlite-ioerr-short-read 522)
(def ^:const sqlite-readonly 8)
(def ^:const sqlite-notfound 12)
(def ^:const sqlite-auth 23)
(def ^:const sqlite-deny 1)
(def ^:const sqlite-attach 24)
(def ^:const sqlite-detach 25)
(def ^:const sqlite-open-readonly 0x00000001)
(def ^:const sqlite-open-readwrite 0x00000002)
(def ^:const sqlite-open-create 0x00000004)
(def ^:const sqlite-open-delete-on-close 0x00000008)
(def ^:const sqlite-open-exclusive 0x00000010)
(def ^:const sqlite-open-auto-proxy 0x00000020)
(def ^:const sqlite-open-uri 0x00000040)
(def ^:const sqlite-open-memory 0x00000080)
(def ^:const sqlite-open-main-db 0x00000100)
(def ^:const sqlite-open-temp-db 0x00000200)
(def ^:const sqlite-open-transient-db 0x00000400)
(def ^:const sqlite-open-main-journal 0x00000800)
(def ^:const sqlite-open-temp-journal 0x00001000)
(def ^:const sqlite-open-subjournal 0x00002000)
(def ^:const sqlite-open-super-journal 0x00004000)
(def ^:const sqlite-open-no-mutex 0x00008000)
(def ^:const sqlite-open-full-mutex 0x00010000)
(def ^:const sqlite-open-shared-cache 0x00020000)
(def ^:const sqlite-open-private-cache 0x00040000)
(def ^:const sqlite-open-wal 0x00080000)
(def ^:const sqlite-open-no-follow 0x01000000)
(def ^:const sqlite-open-extended-result-codes 0x02000000)
(def ^:const sqlite-access-exists 0)
(def ^:const sqlite-access-readwrite 1)
(def ^:const sqlite-access-read 2)
(def ^:const sqlite-database-open-flags
  (bit-or sqlite-open-readwrite sqlite-open-private-cache))
(def ^:const sqlite-dbconfig-enable-load-extension 1005)
(def ^:const sqlite-dbconfig-no-checkpoint-on-close 1006)
(def ^:const sqlite-dbconfig-defensive 1010)
(def ^:const sqlite-dbconfig-trusted-schema 1017)

(defmethod spec-for ::handle [_]
  [:fn #(instance? MemorySegment %)])

(defonce ^:private api (atom nil))

(defn- db-config-handle []
  (let [pointer    ::mem/pointer
        descriptor (FunctionDescriptor/of
                     (mem/c-layout ::mem/int)
                     (into-array MemoryLayout [(mem/c-layout pointer)
                                               (mem/c-layout ::mem/int)
                                               (mem/c-layout ::mem/int)
                                               (mem/c-layout pointer)]))]
    (.downcallHandle (Linker/nativeLinker)
                     (coffi/find-symbol "sqlite3_db_config")
                     descriptor
                     (into-array Linker$Option [(Linker$Option/firstVariadicArg 2)]))))

(defn- create-api []
  (let [pointer ::mem/pointer]
    {:libversion            (coffi/cfn "sqlite3_libversion" [] ::mem/c-string)
     :db-config             (db-config-handle)
     :enable-load-extension (coffi/cfn "sqlite3_enable_load_extension"
                              [pointer ::mem/int]
                              ::mem/int)
     :set-authorizer        (coffi/cfn "sqlite3_set_authorizer"
                              [pointer pointer pointer]
                              ::mem/int)
     :open-v2               (coffi/cfn "sqlite3_open_v2"
                              [::mem/c-string pointer ::mem/int ::mem/c-string]
                              ::mem/int)
     :errmsg                (coffi/cfn "sqlite3_errmsg" [pointer] ::mem/c-string)
     :extended-errcode      (coffi/cfn "sqlite3_extended_errcode" [pointer] ::mem/int)
     :close                 (coffi/cfn "sqlite3_close" [pointer] ::mem/int)
     :exec                  (coffi/cfn "sqlite3_exec"
                              [pointer ::mem/c-string pointer pointer pointer]
                              ::mem/int)
     :vfs-find              (coffi/cfn "sqlite3_vfs_find" [pointer] pointer)
     :vfs-register          (coffi/cfn "sqlite3_vfs_register" [pointer ::mem/int] ::mem/int)
     :vfs-unregister        (coffi/cfn "sqlite3_vfs_unregister" [pointer] ::mem/int)}))

(defn load!
  {:malli/schema [:-> :string :map]}
  [library]
  (try
    (coffi/load-library library)
    (or @api (reset! api (create-api)))
    (catch VirtualMachineError throwable
      (throw throwable))
    (catch Throwable throwable
      (fault "Unable to load SQLite native library"
             {:reason  ::library-load-failed
              :library library}
             throwable))))

(defn version
  {:malli/schema [:-> :string]}
  []
  ((:libversion @api)))

(defn db-config [database option value]
  (let [^MethodHandle handle (:db-config @api)]
    (.intValue ^Integer (.invokeWithArguments handle
                          (object-array [database
                                         (int option)
                                         (int value)
                                         mem/null])))))

(defn enable-load-extension [database enabled-flag]
  ((:enable-load-extension @api) database enabled-flag))

(defn set-authorizer [database authorizer context]
  ((:set-authorizer @api) database authorizer context))

(defn open-v2 [path output flags vfs-name]
  ((:open-v2 @api) path output flags vfs-name))

(defn close [database]
  ((:close @api) database))

(defn errmsg [database]
  ((:errmsg @api) database))

(defn extended-errcode [database]
  ((:extended-errcode @api) database))

(defn exec [database sql callback callback-context error-message]
  ((:exec @api) database sql callback callback-context error-message))

(defn vfs-find [name]
  ((:vfs-find @api) name))

(defn vfs-register [native-vfs]
  ((:vfs-register @api) native-vfs 0))

(defn vfs-unregister [native-vfs]
  ((:vfs-unregister @api) native-vfs))
