(ns linear.adapter.pagestore.impl.sqlite.driver-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [coffi.ffi :as ffi]
   [cognitect.anomalies :as anomaly]
   [integrant.core :as integrant]
   [linear.adapter.pagestore.impl.sqlite.driver :as driver]
   [linear.adapter.pagestore.impl.sqlite.fixture :as fixture]
   [linear.adapter.pagestore.impl.sqlite.session :as session]))

(deftest sqlite-library-is-loadable
  (driver/load-sqlite! (fixture/sqlite-library))
  (is (re-matches #"\d+\.\d+\.\d+" (driver/sqlite-version))))

(deftest guarded-upcall-returns-its-sqlite-result
  (let [failure (driver/new-failure-slot)]
    (is (= driver/sqlite-ok
           (driver/guarded-upcall failure {:operation :x-read :file :main-db}
                                  (constantly driver/sqlite-ok))))
    (is (nil? @failure))))

(deftest guarded-upcall-preserves-an-anomaly
  (let [failure (driver/new-failure-slot)
        cause   (ex-info "missing page"
                         {::anomaly/category ::anomaly/not-found
                          :page-number       7})]
    (testing "the failure is made available to the Clojure caller after SQLite returns"
      (is (= driver/sqlite-ioerr
             (driver/guarded-upcall failure {:operation :x-read :file :main-db}
                                    #(throw cause))))
      (is (= ::anomaly/not-found (::anomaly/category @failure)))
      (is (= :x-read (:operation @failure)))
      (is (= :main-db (:file @failure)))
      (is (identical? cause (:cause @failure))))))

(deftest guarded-upcall-normalizes-external-exception-info-without-an-anomaly
  (let [failure (driver/new-failure-slot)
        cause   (ex-info "foreign failure" {:code :bad-handle})]
    (is (= driver/sqlite-ioerr
           (driver/guarded-upcall failure {:operation :x-read :file :main-db}
                                  #(throw cause))))
    (is (= ::anomaly/fault (::anomaly/category @failure)))
    (is (= :bad-handle (:code @failure)))
    (is (identical? cause (:cause @failure)))))

(deftest guarded-upcall-normalizes-unexpected-throwables
  (let [failure (driver/new-failure-slot)
        cause   (AssertionError. "broken native invariant")]
    (is (= driver/sqlite-ioerr
           (driver/guarded-upcall failure {:operation :x-write :file :wal}
                                  #(throw cause))))
    (is (= ::anomaly/fault (::anomaly/category @failure)))
    (is (= :x-write (:operation @failure)))
    (is (identical? cause (:cause @failure)))))

(deftest guarded-upcall-retains-a-virtual-machine-error-without-a-failure-map
  (let [failure (driver/new-failure-slot)
        cause   (OutOfMemoryError. "native callback allocation failed")]
    (is (= driver/sqlite-ioerr
           (driver/guarded-upcall failure {:operation :x-read :file :main-db}
                                  #(throw cause))))
    (is (identical? cause @failure))))

(deftest guarded-upcall-records-only-the-first-failure
  (let [failure      (driver/new-failure-slot)
        first-cause  (ex-info "first" {::anomaly/category ::anomaly/busy})
        second-cause (ex-info "second" {::anomaly/category ::anomaly/fault})]
    (driver/guarded-upcall failure {:operation :x-read :file :main-db}
                           #(throw first-cause))
    (driver/guarded-upcall failure {:operation :x-sync :file :wal}
                           #(throw second-cause))
    (is (= :x-read (:operation @failure)))
    (is (identical? first-cause (:cause @failure)))))

(deftest load-sqlite-translates-an-external-exception-info
  (let [external-data {:code :not-found}]
    (with-redefs [ffi/load-library (fn [_] (throw (ex-info "missing" external-data)))]
      (let [error (try
                    (driver/load-sqlite! "/missing/libsqlite3.so")
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      error))]
        (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
        (is (= ::driver/sqlite-library-load-failed (-> error ex-data :reason)))
        (is (= external-data (-> error ex-data :external-data)))))))

(deftest load-sqlite-translates-an-external-throwable
  (let [cause (UnsatisfiedLinkError. "missing")]
    (with-redefs [ffi/load-library (fn [_] (throw cause))]
      (let [error (try
                    (driver/load-sqlite! "/missing/libsqlite3.so")
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      error))]
        (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
        (is (= ::driver/sqlite-library-load-failed (-> error ex-data :reason)))
        (is (identical? cause (.getCause error)))))))

(deftest load-sqlite-rethrows-a-virtual-machine-error
  (let [cause (OutOfMemoryError. "native library allocation failed")]
    (with-redefs [ffi/load-library (fn [_] (throw cause))]
      (let [error (try
                    (driver/load-sqlite! "/missing/libsqlite3.so")
                    nil
                    (catch Throwable error
                      error))]
        (is (identical? cause error))))))

(deftest vfs-registration-keeps-its-native-arena-until-unregistration
  (let [runtime (driver/install-vfs! {:library (fixture/sqlite-library)
                                      :name    (str "linear-test-" (random-uuid))})]
    (try
      (is (map? runtime))
      (finally
        (driver/close-vfs! runtime)))))

(deftest vfs-is-an-integrant-component
  (let [key     :linear.adapter.pagestore.impl.sqlite/vfs
        runtime (integrant/init-key key {:library (fixture/sqlite-library)
                                         :name    (str "linear-test-" (random-uuid))})]
    (try
      (is (map? runtime))
      (finally
        (integrant/halt-key! key runtime)))))

(deftest close-vfs-rejects-active-sessions
  (let [path        (str "/linear/" (random-uuid) ".db")
        runtime     (driver/install-vfs! {:library (fixture/sqlite-library)
                                          :name    (str "linear-test-" (random-uuid))})
        close-error (atom nil)]
    (try
      (driver/register-session! runtime path (session/new-session ::database))
      (let [error (try
                    (driver/close-vfs! runtime)
                    nil
                    (catch clojure.lang.ExceptionInfo exception
                      (ex-data exception)))]
        (reset! close-error error)
        (is (= ::driver/active-sessions (:reason error)))
        (is (= [path] (:paths error))))
      (finally
        (driver/unregister-session! runtime path)
        (when @close-error
          (driver/close-vfs! runtime))))))

(deftest registered-vfs-opens-and-reads-a-pagestore-backed-database
  (let [name     (str "linear-test-" (random-uuid))
        path     (str "/linear/" (random-uuid) ".db")
        database (fixture/database "r0" (fixture/sqlite-image))
        session  (session/new-session database)
        runtime  (driver/install-vfs! {:library (fixture/sqlite-library) :name name})]
    (try
      (driver/register-session! runtime path session)
      (let [sqlite (try
                     (driver/open-database! path driver/sqlite-open-readwrite name)
                     (catch clojure.lang.ExceptionInfo _ nil))]
        (is sqlite)
        (is (nil? @(session/failure-slot session)))
        (is (nil? @(:failure runtime)))
        (when sqlite
          (try
            (is (= driver/sqlite-ok
                   (driver/execute-sql! sqlite "PRAGMA locking_mode=EXCLUSIVE")))
            (is (= driver/sqlite-ok
                   (driver/execute-sql! sqlite "PRAGMA wal_autocheckpoint=0")))
            (is (= driver/sqlite-ok
                   (driver/execute-sql! sqlite "PRAGMA synchronous=FULL")))
            (is (= driver/sqlite-ok
                   (driver/execute-sql! sqlite "PRAGMA quick_check")))
            (is (= driver/sqlite-ok
                   (driver/execute-sql! sqlite "BEGIN; UPDATE t SET value='captured'; COMMIT")))
            (is (seq (session/committed-deltas session)))
            (finally
              (session/begin-close! session)
              (is (= driver/sqlite-ok (driver/close-database! sqlite))))))
        (is (nil? @(session/failure-slot session))))
      (finally
        (driver/unregister-session! runtime path)
        (driver/close-vfs! runtime)))))

(deftest open-flags-include-private-cache-and-opened-database-denies-attach
  (driver/load-sqlite! (fixture/sqlite-library))
  (let [sqlite (driver/open-database! ":memory:" driver/sqlite-open-readwrite nil)]
    (try
      (is (= driver/sqlite-open-privatecache
             (bit-and driver/sqlite-open-readwrite driver/sqlite-open-privatecache)))
      (is (= driver/sqlite-auth
             (driver/execute-sql! sqlite "ATTACH DATABASE ':memory:' AS auxiliary")))
      (finally
        (is (= driver/sqlite-ok (driver/close-database! sqlite)))))))
