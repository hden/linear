(ns linear.adapter.slatedb.store-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.crypto.core :as crypto-core]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.store]
   [linear.usecase.database.revisions :as revisions])
  (:import
   (java.util Arrays)))

(defn- database-record [keychain]
  {:id "d-1"
   :display-name "Primary"
   :vault {:id "v-1"
           :created #inst "2026-01-01"
           :keychain keychain}})

(defn- revision-records [keychain revision]
  (let [revision-id  (:revision-id revision)
        revision-key (key/revision revision-id)]
    (into [[revision-key
            (codec/encode-revision keychain {:record-key revision-key :revision revision})]]
          (concat
            (map (fn [[page-id page]]
                   (let [page-key (key/page page-id)]
                     [page-key (codec/encode-page keychain {:record-key page-key :page page})]))
                 (:pages revision))
            [[(key/head) (codec/encode-head {:revision-id revision-id})]]))))

(defn- seed! [store keychain revision]
  (with-open [database    (connection/database store {:database-id "d-1"})
              transaction (connection/writable-transaction database)]
    (ffi/await (ffi/write-values transaction
                                 (revision-records keychain revision)))
    (ffi/await (ffi/commit-transaction transaction))))

(defn- read-records [store record-keys]
  (with-open [database    (connection/database store {:database-id "d-1"})
              transaction (connection/writable-transaction database)]
    (try
      (ffi/await (ffi/read-transaction-values transaction record-keys))
      (finally
        (ffi/await (ffi/rollback-transaction transaction))))))

(deftest ^:integration initialization-encrypts-root-and-refuses-to-replace-an-existing-head
  (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1})
        keychain (crypto-core/new-keychain)
        database (database-record keychain)
        root {:revision-id "r-created" :parent nil :database-page-count 1 :pages {1 (byte-array [1 2 3])}}]
    (try
      (is (= root (revisions/initialize! store {:database database :revision root})))
      (let [[head-value page-value] (read-records store [(key/head) (key/page 1)])]
        (is (= {:revision-id "r-created"} (codec/decode-head head-value)))
        (is (not (Arrays/equals ^bytes (get-in root [:pages 1]) ^bytes page-value)))
        (is (Arrays/equals ^bytes (get-in root [:pages 1])
              ^bytes (codec/decode-page keychain {:record-key (key/page 1) :value page-value}))))
      (let [error (try
                    (revisions/initialize! store {:database database :revision (assoc root :revision-id "r-overwrite")})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::revisions/revision-conflict (:reason (ex-data error)))))
      (let [[head-value overwritten] (read-records store [(key/head) (key/revision "r-overwrite")])]
        (is (= {:revision-id "r-created"} (codec/decode-head head-value)))
        (is (nil? overwritten)))
      (finally (connection/close store)))))

(deftest ^:integration initialization-refuses-a-stored-head-without-a-revision-id
  (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1})
        keychain (crypto-core/new-keychain)]
    (try
      (with-open [database (connection/database store {:database-id "d-1"})
                  transaction (connection/writable-transaction database)]
        (ffi/await (ffi/write-values transaction [[(key/head) (codec/encode-head {})]]))
        (ffi/await (ffi/commit-transaction transaction)))
      (let [error (try
                    (revisions/initialize! store
                      {:database (database-record keychain)
                       :revision {:revision-id "r-created" :parent nil :database-page-count 0 :pages {}}})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::revisions/revision-conflict (:reason (ex-data error)))))
      (let [[head-value root-value] (read-records store [(key/head) (key/revision "r-created")])]
        (is (= {} (codec/decode-head head-value)))
        (is (nil? root-value)))
      (finally (connection/close store)))))

(deftest ^:integration failed-consistent-view-acquisition-returns-the-database-lease
  (let [store    (connection/open {:object-store-url   "memory:///"
                                   :max-open-databases 1})
        keychain (crypto-core/new-keychain)]
    (try
      (with-open [leased-database (connection/database store {:database-id "d-1"})
                  transaction     (connection/writable-transaction leased-database)]
        (ffi/await
          (ffi/write-values transaction [[(key/head) (byte-array [0])]]))
        (ffi/await (ffi/commit-transaction transaction)))
      (is (thrown? Exception
                   (revisions/with-consistent-view
                     [view store (database-record keychain)]
                     (revisions/head view))))
      (with-open [leased-database (connection/database store {:database-id "d-1"})
                  transaction     (connection/writable-transaction leased-database)]
        (is (some? transaction)))
      (finally
        (connection/close store)))))

(deftest ^:integration publishes-revision-pages-and-head-atomically
  (let [store     (connection/open {:object-store-url   "memory:///"
                                    :max-open-databases 1})
        keychain  (crypto-core/new-keychain)
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
             (revisions/publish-next! store {:revision revision :database database})))
      (let [[head-value revision-value page-value]
            (read-records store [(key/head)
                                 (key/revision "r-next")
                                 (key/page 1)])]
        (is (= {:revision-id "r-next"} (codec/decode-head head-value)))
        (is (= (dissoc revision :pages)
               (dissoc (codec/decode-revision keychain {:record-key (key/revision "r-next") :value revision-value})
                       :pages)))
        (is (Arrays/equals (byte-array [2])
                           (codec/decode-page keychain {:record-key (key/page 1) :value page-value}))))
      (finally
        (connection/close store)))))

(deftest ^:integration stale-parent-publishes-no-records
  (let [store     (connection/open {:object-store-url   "memory:///"
                                    :max-open-databases 1})
        keychain  (crypto-core/new-keychain)
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
                    (revisions/publish-next! store {:revision stale :database database})
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
                           (codec/decode-page keychain {:record-key (key/page 1) :value page-value}))))
      (finally
        (connection/close store)))))

(deftest ^:integration reads-the-latest-snapshot-through-the-domain-capability
  (let [store    (connection/open {:object-store-url   "memory:///"
                                   :max-open-databases 1})
        keychain (crypto-core/new-keychain)
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

(deftest ^:integration reads-as-of-and-changes-since-through-one-domain-capability
  (let [store     (connection/open {:object-store-url   "memory:///"
                                    :max-open-databases 1})
        keychain  (crypto-core/new-keychain)
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
             (revisions/publish-next! store {:revision first :database database})))
      (is (= second
             (revisions/publish-next! store {:revision second :database database})))
      (revisions/with-consistent-view [view store database]
        (let [as-of (revisions/as-of view "r-first")]
          (is (= "r-first" (revisions/revision-id as-of)))
          (is (= 2 (revisions/size as-of))))
        (is (= #{1 2}
               (revisions/changes-since view {:target-revision-id "r-second" :client-revision-id "r-root"})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (revisions/changes-since view {:target-revision-id "r-second" :client-revision-id "r-missing"}))))
      (finally
        (connection/close store)))))

(deftest ^:integration callback-failures-return-the-database-lease
  (doseq [failure [(ex-info "Callback failed" {}) (AssertionError. "Callback failed")]]
    (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1})
          borrowed (atom nil)
          returned (atom 0)
          observed-store (assoc store
                                :borrow-database
                                (fn [id]
                                  (let [raw ((:borrow-database store) id)]
                                    (reset! borrowed raw)
                                    raw))
                                :return-database
                                (fn [id raw]
                                  (swap! returned inc)
                                  ((:return-database store) id raw)))
          database (database-record (crypto-core/new-keychain))]
      (try
        (let [caught (try
                       (revisions/read-consistently observed-store {:f (fn [_] (throw failure)) :database database})
                       (catch Exception error error)
                       (catch AssertionError error error))]
          (is (identical? failure caught))
          (is (= 1 @returned))
          (when (= 1 @returned)
            (with-open [lease (connection/database store {:database-id "d-1"})]
              (is (some? lease)))))
        (finally
          (when (and @borrowed (zero? @returned))
            ((:return-database store) "d-1" @borrowed))
          (connection/close store))))))
