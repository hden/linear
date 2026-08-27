(ns linear.adapter.slatedb.store-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.store]
   [linear.usecase.database :as database]
   [taoensso.tempel :as tempel])
  (:import
   (java.util Arrays)))

(defn- database-record [keychain]
  {:id "d-1"
   :display-name "Primary"
   :keychain keychain})

(defn- revision-records [keychain revision]
  (let [revision-id  (:revision-id revision)
        revision-key (key/revision revision-id)]
    (into [[revision-key
            (codec/encode-revision keychain revision-key revision)]]
          (concat
            (map (fn [[page-id page]]
                   (let [page-key (key/page page-id)]
                     [page-key (codec/encode-page keychain page-key page)]))
                 (:pages revision))
            [[(key/head) (codec/encode-head {:revision-id revision-id})]]))))

(defn- seed! [store keychain revision]
  (with-open [database    (connection/database store "d-1")
              transaction (connection/writable-transaction database)]
    (ffi/await (ffi/write-values transaction
                                 (revision-records keychain revision)))
    (ffi/await (ffi/commit-transaction transaction))))

(defn- read-records [store record-keys]
  (with-open [database    (connection/database store "d-1")
              transaction (connection/writable-transaction database)]
    (try
      (ffi/await (ffi/read-transaction-values transaction record-keys))
      (finally
        (ffi/await (ffi/rollback-transaction transaction))))))

(deftest failed-latest-snapshot-acquisition-returns-the-database-lease
  (let [store    (connection/open {:object-store-url   "memory:///"
                                   :max-open-databases 1})
        database (database-record (tempel/keychain))]
    (try
      (with-open [leased-database (connection/database store "d-1")
                  transaction     (connection/writable-transaction leased-database)]
        (ffi/await
          (ffi/write-values transaction [[(key/head) (byte-array [0])]]))
        (ffi/await (ffi/commit-transaction transaction)))
      (is (thrown? Exception
                   (database/latest-snapshot store database)))
      (with-open [leased-database (connection/database store "d-1")
                  transaction     (connection/writable-transaction leased-database)]
        (is (some? transaction)))
      (finally
        (connection/close store)))))

(deftest publishes-revision-pages-and-head-atomically
  (let [store     (connection/open {:object-store-url   "memory:///"
                                    :max-open-databases 1})
        keychain  (tempel/keychain)
        root      {:revision-id         "r-root"
                   :parent              nil
                   :database-page-count 1
                   :pages               {1 (byte-array [1])}}
        revision  {:revision-id         "r-next"
                   :parent              "r-root"
                   :database-page-count 1
                   :pages               {1 (byte-array [2])}}
        database  (database-record keychain)]
    (try
      (seed! store keychain root)
      (is (= revision
             (database/publish-next-revision! store database revision)))
      (let [[head-value revision-value page-value]
            (read-records store [(key/head)
                                 (key/revision "r-next")
                                 (key/page 1)])]
        (is (= {:revision-id "r-next"} (codec/decode-head head-value)))
        (is (= (dissoc revision :pages)
               (dissoc (codec/decode-revision keychain
                                              (key/revision "r-next")
                                              revision-value)
                       :pages)))
        (is (Arrays/equals (byte-array [2])
                           (codec/decode-page keychain (key/page 1) page-value))))
      (finally
        (connection/close store)))))

(deftest stale-parent-publishes-no-records
  (let [store     (connection/open {:object-store-url   "memory:///"
                                    :max-open-databases 1})
        keychain  (tempel/keychain)
        root      {:revision-id         "r-root"
                   :parent              nil
                   :database-page-count 1
                   :pages               {1 (byte-array [1])}}
        stale     {:revision-id         "r-stale"
                   :parent              "r-missing"
                   :database-page-count 1
                   :pages               {1 (byte-array [9])}}
        database  (database-record keychain)]
    (try
      (seed! store keychain root)
      (let [error (try
                    (database/publish-next-revision! store database stale)
                    (catch clojure.lang.ExceptionInfo failure
                      failure))]
        (is (= ::anomaly/conflict (-> error ex-data ::anomaly/category)))
        (is (= ::database/revision-conflict (-> error ex-data :reason))))
      (let [[head-value revision-value page-value]
            (read-records store [(key/head)
                                 (key/revision "r-stale")
                                 (key/page 1)])]
        (is (= {:revision-id "r-root"} (codec/decode-head head-value)))
        (is (nil? revision-value))
        (is (Arrays/equals (byte-array [1])
                           (codec/decode-page keychain (key/page 1) page-value))))
      (finally
        (connection/close store)))))

(deftest reads-the-latest-snapshot-through-the-domain-capability
  (let [store    (connection/open {:object-store-url   "memory:///"
                                   :max-open-databases 1})
        keychain (tempel/keychain)
        root     {:revision-id "r-root"
                  :parent nil
                  :database-page-count 1
                  :pages {1 (byte-array [7])}}
        database (database-record keychain)]
    (try
      (seed! store keychain root)
      (with-open [snapshot (database/latest-snapshot store database)]
        (is (= "r-root" (database/revision-id snapshot)))
        (is (= 1 (database/size snapshot)))
        (is (Arrays/equals
              (byte-array [7])
              (get (database/fetch-pages-by-ids snapshot {:ids #{1}}) 1))))
      (finally
        (connection/close store)))))
