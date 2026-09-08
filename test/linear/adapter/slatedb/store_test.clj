(ns linear.adapter.slatedb.store-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.store]
   [linear.usecase.database.revisions :as revisions]
   [taoensso.tempel :as tempel])
  (:import
   (java.util Arrays)))

(defn- database-record [keychain]
  {:id "d-1"
   :display-name "Primary"
   :vault {:id "v-1"
           :owner "owner-1"
           :created #inst "2026-01-01"
           :keychain keychain}})

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

(deftest failed-consistent-view-acquisition-returns-the-database-lease
  (let [store    (connection/open {:object-store-url   "memory:///"
                                   :max-open-databases 1})
        keychain (crypto/keychain (tempel/keychain))]
    (try
      (with-open [leased-database (connection/database store "d-1")
                  transaction     (connection/writable-transaction leased-database)]
        (ffi/await
          (ffi/write-values transaction [[(key/head) (byte-array [0])]]))
        (ffi/await (ffi/commit-transaction transaction)))
      (is (thrown? Exception
                   (revisions/with-consistent-view
                     [view store (database-record keychain)]
                     (revisions/head view))))
      (with-open [leased-database (connection/database store "d-1")
                  transaction     (connection/writable-transaction leased-database)]
        (is (some? transaction)))
      (finally
        (connection/close store)))))

(deftest publishes-revision-pages-and-head-atomically
  (let [store     (connection/open {:object-store-url   "memory:///"
                                    :max-open-databases 1})
        keychain  (crypto/keychain (tempel/keychain))
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
             (revisions/publish-next! store revision database)))
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
        keychain  (crypto/keychain (tempel/keychain))
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
                    (revisions/publish-next! store stale database)
                    (catch clojure.lang.ExceptionInfo failure
                      failure))]
        (is (= ::anomaly/conflict (-> error ex-data ::anomaly/category)))
        (is (= ::revisions/revision-conflict (-> error ex-data :reason))))
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
        keychain (crypto/keychain (tempel/keychain))
        root     {:revision-id "r-root"
                  :parent nil
                  :database-page-count 1
                  :pages {1 (byte-array [7])}}
        database (database-record keychain)]
    (try
      (seed! store keychain root)
      (revisions/with-consistent-view [view store database]
        (let [snapshot (revisions/head view)]
          (is (= "r-root" (revisions/revision-id snapshot)))
          (is (= 1 (revisions/size snapshot)))
          (is (Arrays/equals
                (byte-array [7])
                (get (revisions/fetch-pages-by-ids snapshot {:ids #{1}}) 1)))))
      (finally
        (connection/close store)))))

(deftest reads-as-of-and-changes-since-through-one-domain-capability
  (let [store     (connection/open {:object-store-url   "memory:///"
                                    :max-open-databases 1})
        keychain  (crypto/keychain (tempel/keychain))
        root      {:revision-id         "r-root"
                   :parent              nil
                   :database-page-count 1
                   :pages               {1 (byte-array [1])}}
        first     {:revision-id         "r-first"
                   :parent              "r-root"
                   :database-page-count 2
                   :pages               {2 (byte-array [2])}}
        second    {:revision-id         "r-second"
                   :parent              "r-first"
                   :database-page-count 2
                   :pages               {1 (byte-array [3])}}
        database  (database-record keychain)]
    (try
      (seed! store keychain root)
      (is (= first
             (revisions/publish-next! store first database)))
      (is (= second
             (revisions/publish-next! store second database)))
      (revisions/with-consistent-view [view store database]
        (let [as-of (revisions/as-of view "r-first")]
          (is (= "r-first" (revisions/revision-id as-of)))
          (is (= 2 (revisions/size as-of))))
        (is (= #{1 2}
               (revisions/changes-since view "r-second" "r-root")))
        (is (thrown? clojure.lang.ExceptionInfo
                     (revisions/changes-since view "r-second" "r-missing"))))
      (finally
        (connection/close store)))))
