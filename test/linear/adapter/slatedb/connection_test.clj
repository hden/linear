(ns linear.adapter.slatedb.connection-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [promesa.core :as p]
   [promesa.exec :as px]))

(defn- new-connection []
  (connection/open {:object-store-url "memory:///" :max-open-databases 1}))

(defn- use-database [connection {:keys [id]}]
  (connection/with-database [database connection {:database-id id}]
    (ffi/usable-database? database)))

(deftest ^:integration reader-waits-until-the-writer-returns-the-database
  (let [connection (new-connection)
        ready (p/deferred)
        finish (p/deferred)
        writer (p/thread
                 (connection/with-database [database connection {:database-id "a"}]
                   (ffi/with-transaction [transaction database]
                     (try
                       (ffi/await (ffi/write-values transaction [[(byte-array [1]) (byte-array [2])]]))
                       (p/resolve ready true)
                       (p/join finish)
                       (finally (ffi/await (ffi/rollback-transaction transaction)))))))]
    (try
      (is (= true (p/await ready 5000 ::timeout)))
      (let [reader (p/thread
                     (connection/with-database [database connection {:database-id "a"}]
                       (ffi/with-snapshot [snapshot database]
                         (ffi/await (ffi/read-snapshot-values snapshot [(byte-array [1])])))))]
        (try (is (= ::waiting (p/await reader 100 ::waiting)))
             (p/resolve finish true)
             (is (= [nil] (p/await reader 5000 ::timeout)))
             (finally (p/resolve finish true) (p/await reader 15000 ::timeout))))
      (finally
        (p/resolve finish true)
        (p/await writer 15000 ::timeout)
        (connection/close connection)))))

(deftest ^:integration writers-serialize-and-interruption-releases-their-use
  (let [connection (new-connection)
        ready (p/deferred)
        finish (p/deferred)
        writer (p/thread
                 (connection/with-database [_ connection {:database-id "a"}]
                   (p/resolve ready true)
                   (p/join finish)))
        attempting (p/deferred)
        entered (p/deferred)
        interrupted (p/deferred)]
    (try
      (is (= true (p/await ready 5000 ::timeout)))
      (let [request-thread (p/deferred)
            next-writer (p/thread
                          (p/resolve request-thread (px/current-thread))
                          (p/resolve attempting true)
                          (try
                            (connection/with-database [_ connection {:database-id "a"}]
                              (p/resolve entered true))
                            (catch InterruptedException _ (p/resolve interrupted true))))]
        (try
          (is (= true (p/await attempting 5000 ::timeout)))
          (is (= ::waiting (p/await entered 100 ::waiting)))
          (px/interrupt (p/join request-thread))
          (is (= true (p/await interrupted 5000 ::timeout)))
          (finally (p/await next-writer 15000 ::timeout))))
      (p/resolve finish true)
      (is (= true (p/await writer 5000 ::timeout)))
      (is (use-database connection {:id "b"}))
      (finally
        (p/resolve finish true)
        (p/await writer 15000 ::timeout)
        (connection/close connection)))))

(deftest ^:integration active-snapshot-survives-capacity-pressure
  (let [connection (new-connection)
        ready (p/deferred)
        finish (p/deferred)
        reader (p/thread
                 (connection/with-database [database connection {:database-id "a"}]
                   (ffi/with-snapshot [snapshot database]
                     (p/resolve ready true)
                     (p/join finish)
                     (ffi/await (ffi/read-snapshot-values snapshot [(byte-array [1])])))))
        attempting (p/deferred)]
    (try
      (is (= true (p/await ready 5000 ::timeout)))
      (let [other (p/thread (p/resolve attempting true) (use-database connection {:id "b"}))]
        (try
          (is (= true (p/await attempting 5000 ::timeout)))
          (is (= ::waiting (p/await other 100 ::waiting)))
          (p/resolve finish true)
          (is (= [nil] (p/await reader 5000 ::timeout)))
          (is (= true (p/await other 5000 ::timeout)))
          (finally (p/resolve finish true) (p/await other 15000 ::timeout))))
      (finally
        (p/resolve finish true)
        (p/await reader 15000 ::timeout)
        (connection/close connection)))))

(deftest ^:integration subscription-wakes-on-eviction-without-holding-capacity
  (let [connection (new-connection)]
    (try
      (ffi/with-change-notifications [await-change (connection/subscribe connection {:database-id "a"})]
        (is (use-database connection {:id "b"}))
        (let [waiting (p/thread (await-change {:timeout-ms 10000}))]
          (try
            (is (nil? (p/await waiting 1000 ::timeout)))
            (is (use-database connection {:id "a"}))
            (finally (p/await waiting 15000 ::timeout)))))
      (finally (connection/close connection)))))

(deftest ^:integration invalid-handles-and-callback-errors-release-capacity
  (let [connection (new-connection)]
    (try
      (is (thrown-with-msg? Exception #"callback"
            (connection/with-database [_ connection {:database-id "a"}]
              (throw (ex-info "callback" {})))))
      (connection/with-database [database connection {:database-id "a"}]
        (ffi/await (ffi/close-database! database)))
      (is (use-database connection {:id "a"}))
      (is (use-database connection {:id "b"}))
      (finally (connection/close connection)))))

(deftest ^:integration close-rejects-new-borrows-without-waiting-for-active-borrow
  (let [connection (new-connection)]
    (try
      (connection/with-database [database connection {:database-id "a"}]
        (is (nil? (connection/close connection)))
        (is (ffi/usable-database? database))
        (is (thrown? IllegalStateException (use-database connection {:id "b"}))))
      (is (thrown? IllegalStateException (use-database connection {:id "a"})))
      (finally (connection/close connection)))))

(deftest ^:integration concurrent-first-access-serializes-all-borrows
  (let [connection (new-connection)
        start (p/deferred)
        active (atom 0)
        readers (mapv (fn [_]
                        (p/thread
                          (p/join start)
                          (connection/with-database [database connection {:database-id "a"}]
                            (try
                              (is (= 1 (swap! active inc)))
                              (ffi/usable-database? database)
                              (finally (swap! active dec))))))
                      (range 8))]
    (try
      (p/resolve start true)
      (doseq [reader readers] (is (= true (p/await reader 5000 ::timeout))))
      (finally
        (p/resolve start true)
        (doseq [reader readers] (p/await reader 15000 ::timeout))
        (connection/close connection)))))

(deftest ^:integration interrupted-capacity-wait-does-not-block-later-acquisition
  (let [connection (new-connection)
        ready (p/deferred)
        finish (p/deferred)
        interrupted (p/deferred)
        reader (p/thread
                 (connection/with-database [_ connection {:database-id "a"}]
                   (p/resolve ready true)
                   (p/join finish)))]
    (try
      (is (= true (p/await ready 5000 ::timeout)))
      (let [request-thread (p/deferred)
            waiter (p/thread
                     (p/resolve request-thread (px/current-thread))
                     (try (use-database connection {:id "b"})
                       (catch InterruptedException _ (p/resolve interrupted true))))]
        (try
          (is (= ::waiting (p/await waiter 100 ::waiting)))
          (px/interrupt (p/join request-thread))
          (is (= true (p/await interrupted 5000 ::timeout)))
          (p/resolve finish true)
          (is (= true (p/await reader 5000 ::timeout)))
          (is (use-database connection {:id "b"}))
          (finally (p/await waiter 15000 ::timeout))))
      (finally
        (p/resolve finish true)
        (p/await reader 15000 ::timeout)
        (connection/close connection)))))

(deftest ^:integration fenced-handle-is-retired-before-the-next-acquisition
  (let [connection (new-connection)]
    (try
      (connection/with-database [database connection {:database-id "a"}]
        (let [replacement (ffi/await (ffi/open-database! (:object-store connection) "a"))]
          (try
            (let [error (try
                          (ffi/with-transaction [transaction database]
                            (ffi/await (ffi/write-values transaction [[(byte-array [1]) (byte-array [2])]]))
                            (ffi/await (ffi/commit-transaction transaction)))
                          nil
                          (catch Exception error error))]
              (is (= ::ffi/fenced (:reason (ex-data error)))))
            (finally (ffi/await (ffi/close-database! replacement))))))
      (is (use-database connection {:id "a"}))
      (is (use-database connection {:id "b"}))
      (finally (connection/close connection)))))

(deftest ^:integration failed-open-does-not-leave-a-capacity-reservation
  (let [connection (new-connection)]
    (try
      (is (thrown? Exception (use-database connection {:id nil})))
      (is (use-database connection {:id "a"}))
      (is (thrown? Exception (use-database connection {:id nil})))
      (is (use-database connection {:id "b"}))
      (finally (connection/close connection)))))

(deftest ^:integration interrupted-first-acquisition-does-not-cancel-lifecycle-work
  (let [connection (new-connection)
        request (p/thread
                  (px/interrupt)
                  (try
                    (connection/with-database [_ connection {:database-id "a"}]
                      true)
                    (catch InterruptedException _ ::interrupted)))]
    (try
      (is (= ::interrupted (p/await request 5000 ::timeout)))
      (is (use-database connection {:id "a"}))
      (is (use-database connection {:id "b"}))
      (finally
        (p/await request 15000 ::timeout)
        (connection/close connection)))))

(deftest ^:integration interrupted-writer-does-not-let-its-successor-overtake
  (let [connection (new-connection)
        ready (p/deferred)
        finish (p/deferred)
        first-writer (p/thread
                       (connection/with-database [_ connection {:database-id "a"}]
                         (p/resolve ready true)
                         (p/join finish)))
        attempting (p/deferred)
        interrupted (p/deferred)
        entered (p/deferred)]
    (try
      (is (= true (p/await ready 5000 ::timeout)))
      (let [request-thread (p/deferred)
            middle-writer (p/thread
                            (p/resolve request-thread (px/current-thread))
                            (p/resolve attempting true)
                            (try
                              (connection/with-database [_ connection {:database-id "a"}]
                                (throw (AssertionError. "Interrupted writer entered its scope")))
                              (catch InterruptedException _ (p/resolve interrupted true))))]
        (try
          (is (= true (p/await attempting 5000 ::timeout)))
          (is (= ::waiting (p/await middle-writer 100 ::waiting)))
          (px/interrupt (p/join request-thread))
          (is (= true (p/await interrupted 5000 ::timeout)))
          (let [last-writer (p/thread
                              (connection/with-database [_ connection {:database-id "a"}]
                                (p/resolve entered true)))]
            (try
              (is (= ::waiting (p/await entered 100 ::waiting)))
              (p/resolve finish true)
              (is (= true (p/await first-writer 5000 ::timeout)))
              (is (= true (p/await entered 5000 ::timeout)))
              (p/await last-writer 5000 ::timeout)
              (finally (p/resolve finish true) (p/await last-writer 15000 ::timeout))))
          (finally (p/await middle-writer 15000 ::timeout))))
      (finally
        (p/resolve finish true)
        (try (p/await first-writer 5000 ::timeout)
             (finally
               (p/await first-writer 15000 ::timeout)
               (connection/close connection)))))))

(deftest ^:integration competing-databases-progress-with-a-single-open-slot
  (let [connection (new-connection)
        start (p/deferred)
        readers (mapv (fn [id]
                        (p/thread
                          (p/join start)
                          (vec (repeatedly 3 #(use-database connection {:id id})))))
                  ["a" "a" "b" "b"])]
    (try
      (p/resolve start true)
      (doseq [reader readers]
        (is (= [true true true] (p/await reader 15000 ::timeout))))
      (finally
        (p/resolve start true)
        (try
          (doseq [reader readers] (p/await reader 15000 ::timeout))
          (finally (connection/close connection)))))))

(deftest ^:integration subscription-observes-publish-before-waiting
  (let [connection (new-connection)]
    (try
      (ffi/with-change-notifications [await-change (connection/subscribe connection {:database-id "a"})]
        (connection/with-database [database connection {:database-id "a"}]
          (ffi/with-transaction [transaction database]
            (ffi/await (ffi/write-values transaction [[(byte-array [1]) (byte-array [2])]]))
            (ffi/await (ffi/commit-transaction transaction))))
        (let [waiting (p/thread (await-change {:timeout-ms 10000}))]
          (try
            (is (nil? (p/await waiting 1000 ::timeout)))
            (finally (p/await waiting 15000 ::timeout)))))
      (finally (connection/close connection)))))
