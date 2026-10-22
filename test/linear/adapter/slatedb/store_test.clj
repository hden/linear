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
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.keychain :as keychain])
  (:import
   (java.util Arrays)))

(defn- database-record [keychain]
  {:id "d-1"
   :display-name "Primary"
   :vault {:id "v-1"
           :created #inst "2026-01-01"
           :keychain keychain}})

(defn- seed! [store keychain revision]
  (revisions/initialize! store {:database (database-record keychain) :revision revision}))

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
        (is (= {:revision-id "r-created" :database-page-count 1} (select-keys (codec/decode-head head-value) [:revision-id :database-page-count])))
        (is (not (Arrays/equals ^bytes (get-in root [:pages 1]) ^bytes page-value)))
        (is (Arrays/equals ^bytes (get-in root [:pages 1])
              ^bytes (codec/decode-page keychain {:record-key (key/page 1) :value page-value}))))
      (let [error (try
                    (revisions/initialize! store {:database database :revision (assoc root :revision-id "r-overwrite")})
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= ::revisions/revision-conflict (:reason (ex-data error)))))
      (let [[head-value overwritten] (read-records store [(key/head) (key/revision "r-overwrite")])]
        (is (= {:revision-id "r-created" :database-page-count 1} (select-keys (codec/decode-head head-value) [:revision-id :database-page-count])))
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
        (is (= {} (select-keys (codec/decode-head head-value) [:revision-id :database-page-count])))
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
        (is (= {:revision-id "r-next" :database-page-count 1} (select-keys (codec/decode-head head-value) [:revision-id :database-page-count])))
        (let [undo (codec/decode-revision keychain {:record-key (key/revision "r-next") :value revision-value})]
          (is (= "r-root" (get-in undo [:parent :revision-id])))
          (is (= 1 (get-in undo [:parent :database-page-count])))
          (is (Arrays/equals (byte-array [1]) (get-in undo [:undo-pages 1]))))
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
        (is (= {:revision-id "r-root" :database-page-count 1} (select-keys (codec/decode-head head-value) [:revision-id :database-page-count])))
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

(defn- expire-history! [store revision-ids]
  (with-open [database (connection/database store {:database-id "d-1"})
              transaction (connection/writable-transaction database)]
    (let [[value] (ffi/await (ffi/read-transaction-values transaction [(key/head)]))
          head (assoc (codec/decode-head value) :history-expires-at 0)]
      (doseq [revision-id revision-ids]
        (ffi/await (.delete ^io.slatedb.uniffi.DbTransaction transaction (key/revision revision-id))))
      (ffi/await (ffi/write-values transaction [[(key/head) (codec/encode-head head)]]))
      (ffi/await (ffi/commit-transaction transaction)))))

(defn- record-expiries [store record-keys]
  (with-open [database (connection/database store {:database-id "d-1"})]
    (mapv (fn [record-key]
            (.expireTs ^io.slatedb.uniffi.KeyValue
              (ffi/await (.getKeyValue ^io.slatedb.uniffi.Db (:raw-database database) record-key))))
          record-keys)))

(deftest ^:integration history-expires-but-current-data-survives-inactivity-and-remains-writable
  (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1})
        keychain (crypto-core/new-keychain)
        database (database-record keychain)
        root {:revision-id "r-root" :parent nil :database-page-count 2
              :pages {1 (byte-array [1]) 2 (byte-array [2])}}
        next {:revision-id "r-next" :parent "r-root" :database-page-count 2
              :pages {1 (byte-array [3])}}
        before (System/currentTimeMillis)]
    (try
      (seed! store keychain root)
      (let [[head-expiry page-expiry revision-expiry]
            (record-expiries store [(key/head) (key/page 1) (key/revision "r-root")])]
        (is (nil? head-expiry))
        (is (nil? page-expiry))
        (is (<= (+ before (* 30 86400000)) revision-expiry)))
      (expire-history! store ["r-root"])
      (revisions/with-consistent-view [view store database]
        (let [snapshot (revisions/head view)]
          (is (= 2 (revisions/size snapshot)))
          (is (= {1 '(1) 2 '(2)}
                 (update-vals (revisions/fetch-pages-by-ids snapshot {:ids #{1 2}}) seq)))))
      (revisions/publish-next! store {:revision next :database database})
      (revisions/with-consistent-view [view store database]
        (let [snapshot (revisions/as-of view "r-root")]
          (is (= 2 (revisions/size snapshot)))
          (is (= {1 '(1) 2 '(2)}
                 (update-vals (revisions/fetch-pages-by-ids snapshot {:ids #{1 2}}) seq)))))
      (expire-history! store ["r-next"])
      (revisions/with-consistent-view [view store database]
        (is (= {1 '(3) 2 '(2)}
               (update-vals (revisions/fetch-pages-by-ids (revisions/head view) {:ids #{1 2}}) seq))))
      (finally (connection/close store)))))

(deftest ^:integration shorter-retention-does-not-create-a-hole-in-the-undo-chain
  (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1 :revision-retention-days 90})
        keychain (crypto-core/new-keychain)
        database (database-record keychain)
        root {:revision-id "r-root" :parent nil :database-page-count 1 :pages {1 (byte-array [1])}}]
    (try
      (seed! store keychain root)
      (revisions/publish-next! (assoc store :revision-retention-days 1)
        {:database database :revision {:revision-id "r-next" :parent "r-root" :database-page-count 1 :pages {1 (byte-array [2])}}})
      (let [[root-expiry next-expiry] (record-expiries store [(key/revision "r-root") (key/revision "r-next")])]
        (is (= root-expiry next-expiry))
        (is (<= (+ (System/currentTimeMillis) (* 89 86400000)) next-expiry)))
      (finally (connection/close store)))))

(deftest ^:integration shrink-regrow-and-repeated-page-updates-preserve-every-state
  (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1})
        keychain (crypto-core/new-keychain)
        database (database-record keychain)
        root {:revision-id "r-root" :parent nil :database-page-count 2
              :pages {1 (byte-array [1]) 2 (byte-array [2])}}]
    (try
      (seed! store keychain root)
      (doseq [revision [{:revision-id "r-shrink" :parent "r-root" :database-page-count 1 :pages {1 (byte-array [3])}}
                        {:revision-id "r-grow" :parent "r-shrink" :database-page-count 3 :pages {2 (byte-array [4]) 3 (byte-array [5])}}
                        {:revision-id "r-repeat" :parent "r-grow" :database-page-count 3 :pages {1 (byte-array [6])}}]]
        (revisions/publish-next! store {:database database :revision revision}))
      (revisions/with-consistent-view [view store database]
        (doseq [[id expected] [["r-root" {1 '(1) 2 '(2) 3 nil}]
                               ["r-shrink" {1 '(3) 2 nil 3 nil}]
                               ["r-grow" {1 '(3) 2 '(4) 3 '(5)}]
                               ["r-repeat" {1 '(6) 2 '(4) 3 '(5)}]]]
          (is (= expected
                 (update-vals (revisions/fetch-pages-by-ids (revisions/as-of view id) {:ids #{1 2 3}}) seq)))))
      (finally (connection/close store)))))

(deftest ^:integration failed-page-encryption-rolls-back-the-staged-undo-and-allows-retry
  (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1})
        keychain (crypto-core/new-keychain)
        database (database-record keychain)
        failing-keychain (reify keychain/Keychain
                           (-encrypt [_ value options]
                             (when (Arrays/equals ^bytes (key/page 2) ^bytes (:associated-data options))
                               (throw (ex-info "Page encryption failed" {})))
                             (keychain/-encrypt keychain value options))
                           (-decrypt [_ value options]
                             (keychain/-decrypt keychain value options)))
        root {:revision-id "r-root" :parent nil :database-page-count 1 :pages {1 (byte-array [1])}}
        next {:revision-id "r-next" :parent "r-root" :database-page-count 2
              :pages (sorted-map 1 (byte-array [2]) 2 (byte-array [3]))}]
    (try
      (seed! store keychain root)
      (is (thrown? clojure.lang.ExceptionInfo
                   (revisions/publish-next! store {:database (assoc-in database [:vault :keychain] failing-keychain) :revision next})))
      (let [[head-value undo-value new-page] (read-records store [(key/head) (key/revision "r-next") (key/page 2)])]
        (is (= "r-root" (:revision-id (codec/decode-head head-value))))
        (is (nil? undo-value))
        (is (nil? new-page)))
      (is (= next (revisions/publish-next! store {:database database :revision next})))
      (revisions/with-consistent-view [view store database]
        (is (= '(1) (seq (get (revisions/fetch-pages-by-ids (revisions/as-of view "r-root") {:ids #{1}}) 1)))))
      (finally (connection/close store)))))

(deftest ^:integration expired-client-cursors-bootstrap-the-fixed-target-but-missing-targets-fail
  (let [store (connection/open {:object-store-url "memory:///" :max-open-databases 1})
        keychain (crypto-core/new-keychain)
        database (database-record keychain)]
    (try
      (seed! store keychain {:revision-id "r-root" :parent nil :database-page-count 1 :pages {1 (byte-array [1])}})
      (revisions/publish-next! store {:database database :revision {:revision-id "r-first" :parent "r-root" :database-page-count 1 :pages {1 (byte-array [2])}}})
      (expire-history! store ["r-root" "r-first"])
      (revisions/publish-next! store {:database database :revision {:revision-id "r-second" :parent "r-first" :database-page-count 2 :pages {2 (byte-array [3])}}})
      (revisions/with-consistent-view [view store database]
        (let [database (model/database database {::model/consistent-view view})
              result (model/pull database {:server-revision "r-first" :client-revision "r-root"})]
          (is (= "r-first" (:server-revision result)))
          (is (= 1 (:database-page-count result)))
          (is (= {1 '(2)} (update-vals (:pages result) seq)))
          (is (thrown? clojure.lang.ExceptionInfo (model/pull database {:server-revision "r-root"})))))
      (finally (connection/close store)))))
