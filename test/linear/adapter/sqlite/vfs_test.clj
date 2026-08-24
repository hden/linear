(ns linear.adapter.sqlite.vfs-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.sqlite.ffi :as ffi]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.adapter.sqlite.vfs :as vfs]
   [promesa.core :as p])
  (:import
   (java.util.concurrent TimeoutException)))

(defn- filesystem []
  (reify sqlite/FileSystem
    (-open [_ _] nil)
    (-delete [_ _] nil)
    (-access [_ _] false)
    (-full-path [_ {:keys [path]}] path)))

(defn- resources []
  {:arena ::arena
   :native-vfs ::native-vfs
   :name "test-vfs"
   :callbacks []
   :state (atom {:status :ready
                 :liveness :ok
                 :paths {}
                 :files {}
                 :callbacks {}
                 :failure nil
                 :drained (promise)})})

(deftest decodes-sqlite-open-flags-at-the-vfs-boundary
  (is (= {:path "/test.db"
          :requested-mode :read-write
          :kind :main-db
          :options #{:create :private-cache}}
         (#'vfs/decode-open-request
           "/test.db"
           (bit-or ffi/sqlite-open-readwrite
                   ffi/sqlite-open-create
                   ffi/sqlite-open-main-db
                   ffi/sqlite-open-private-cache)))))

(deftest open-result-flags-reflect-the-mode-actually-opened
  (let [requested (bit-or ffi/sqlite-open-readwrite
                          ffi/sqlite-open-create
                          ffi/sqlite-open-main-db)
        actual (#'vfs/encode-open-result requested :read-only)]
    (is (zero? (bit-and actual ffi/sqlite-open-readwrite)))
    (is (= ffi/sqlite-open-readonly
           (bit-and actual ffi/sqlite-open-readonly)))
    (is (= ffi/sqlite-open-create
           (bit-and actual ffi/sqlite-open-create)))))

(deftest decodes-sqlite-access-modes-at-the-vfs-boundary
  (is (= :exists (#'vfs/decode-access-mode ffi/sqlite-access-exists)))
  (is (= :read-write (#'vfs/decode-access-mode ffi/sqlite-access-readwrite)))
  (is (= :read (#'vfs/decode-access-mode ffi/sqlite-access-read))))

(deftest routes-paths-to-independent-filesystem-invocations
  (let [resources  (resources)
        first       (vfs/mount resources {:path "/first.db" :filesystem (filesystem)})
        second      (vfs/mount resources {:path "/second.db" :filesystem (filesystem)})]
    (is (identical? first (get-in @(:state resources) [:paths "/first.db-wal"])))
    (is (identical? second (get-in @(:state resources) [:paths "/second.db-shm"])))
    (vfs/unmount first)
    (is (nil? (get-in @(:state resources) [:paths "/first.db"])))
    (is (identical? second (get-in @(:state resources) [:paths "/second.db"])))))

(deftest guarded-callback-records-and-contains-exceptions
  (let [resources  (resources)
        invocation (vfs/mount resources {:path "/test.db" :filesystem (filesystem)})
        result     (#'vfs/guarded-callback
                     resources
                     invocation
                     {:operation :x-read}
                     #(throw (ex-info "read failed" {:reason ::failed})))]
    (is (= ffi/sqlite-ioerr result))
    (is (empty? (:callbacks @(:state resources))))
    (is (= :x-read (:operation (:failure @(:failure invocation)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (vfs/throw-if-failed! invocation)))))

(deftest callback-failure-is-recorded-only-once
  (let [resources  (resources)
        invocation (vfs/mount resources {:path "/test.db" :filesystem (filesystem)})]
    (#'vfs/guarded-callback resources invocation {:operation :x-read}
                            #(throw (ex-info "first" {})))
    (#'vfs/guarded-callback resources invocation {:operation :x-write}
                            #(throw (ex-info "second" {})))
    (is (= :x-read (:operation (:failure @(:failure invocation)))))))

(deftest unrouted-callback-failure-makes-the-vfs-unhealthy
  (let [resources (resources)]
    (#'vfs/guarded-callback resources nil {:operation :x-open}
                            #(throw (ex-info "unrouted" {})))
    (is (false? (vfs/ok? resources)))))

(deftest drain-signals-after-the-last-invocation-closes
  (let [resources  (resources)
        invocation (vfs/mount resources {:path "/test.db" :filesystem (filesystem)})]
    (vfs/drain! resources)
    (is (not (realized? (:drained @(:state resources)))))
    (vfs/unmount invocation)
    (is (realized? (:drained @(:state resources))))))

(deftest draining-rejects-new-invocations
  (let [resources (resources)]
    (vfs/drain! resources)
    (is (thrown? clojure.lang.ExceptionInfo
                 (vfs/mount resources {:path "/late.db" :filesystem (filesystem)})))))

(deftest watchdog-timeout-permanently-fails-liveness
  (let [resources  (resources)
        completion (#'vfs/callback-watchdog resources (Object.) {:operation :x-read})]
    (p/reject completion (TimeoutException.))
    (is (false? (vfs/ok? resources)))))
