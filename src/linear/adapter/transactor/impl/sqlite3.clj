;; (ns linear.adapter.transactor.impl.sqlite3
;;   (:require
;;    [coffi.mem :as mem]
;;    [coffi.layout :as layout]
;;    [coffi.ffi :as ffi]
;;    [integrant.core :as integrant]
;;    [linear.adapter.transactor.core :as core]))

;; (def ^:private ^:const SQLITE_OK 0)
;; (def ^:private ^:const SQLITE_IOERR 10)
;; (def ^:private ^:const SQLITE_IOERR_SHORT_READ 522)
;; (def ^:private ^:const SQLITE_OPEN_MAIN_DB 0x00000100)
;; (def ^:private ^:const SQLITE_NOTFOUND 12)
;; (def ^:private ^:const PAGE 4096)
