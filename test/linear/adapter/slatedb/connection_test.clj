(ns linear.adapter.slatedb.connection-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]))

(defn- new-connection []
  (connection/open {:object-store-url "memory:///" :max-open-databases 1}))

(defn- use-database [connection id]
  (connection/with-database [database connection {:database-id id}]
    (ffi/usable-database? database)))

(deftest ^:integration reader-waits-until-the-writer-returns-the-database
  (let [connection (new-connection)
        ready (promise)
        finish (promise)
        writer (future
                 (connection/with-database [database connection {:database-id "a"}]
                   (ffi/with-transaction [transaction database]
                     (try
                       (ffi/await (ffi/write-values transaction [[(byte-array [1]) (byte-array [2])]]))
                       (deliver ready true)
                       @finish
                       (finally (ffi/await (ffi/rollback-transaction transaction)))))))]
    (try
      (is (= true (deref ready 5000 ::timeout)))
      (let [reader (future
                     (connection/with-database [database connection {:database-id "a"}]
                       (ffi/with-snapshot [snapshot database]
                         (ffi/await (ffi/read-snapshot-values snapshot [(byte-array [1])])))))]
        (try (is (= ::waiting (deref reader 100 ::waiting)))
             (deliver finish true)
             (is (= [nil] (deref reader 5000 ::timeout)))
             (finally (future-cancel reader))))
      (finally
        (deliver finish true)
        (deref writer 5000 ::timeout)
        (future-cancel writer)
        (connection/close connection)))))

(deftest ^:integration writers-serialize-and-interruption-releases-their-use
  (let [connection (new-connection)
        ready (promise)
        finish (promise)
        writer (future
                 (connection/with-database [_ connection {:database-id "a"}]
                   (deliver ready true)
                   @finish))
        attempting (promise)
        entered (promise)
        interrupted (promise)]
    (try
      (is (= true (deref ready 5000 ::timeout)))
      (let [next-writer (future
                          (deliver attempting true)
                          (try
                            (connection/with-database [_ connection {:database-id "a"}]
                              (deliver entered true))
                            (catch InterruptedException _ (deliver interrupted true))))]
        (try
          (is (= true (deref attempting 5000 ::timeout)))
          (is (= ::waiting (deref entered 100 ::waiting)))
          (future-cancel next-writer)
          (is (= true (deref interrupted 5000 ::timeout)))
          (finally (future-cancel next-writer))))
      (deliver finish true)
      (is (= true (deref writer 5000 ::timeout)))
      (is (use-database connection "b"))
      (finally
        (deliver finish true)
        (deref writer 5000 ::timeout)
        (future-cancel writer)
        (connection/close connection)))))

(deftest ^:integration active-snapshot-survives-capacity-pressure
  (let [connection (new-connection)
        ready (promise)
        finish (promise)
        reader (future
                 (connection/with-database [database connection {:database-id "a"}]
                   (ffi/with-snapshot [snapshot database]
                     (deliver ready true)
                     @finish
                     (ffi/await (ffi/read-snapshot-values snapshot [(byte-array [1])])))))
        attempting (promise)]
    (try
      (is (= true (deref ready 5000 ::timeout)))
      (let [other (future (deliver attempting true) (use-database connection "b"))]
        (try
          (is (= true (deref attempting 5000 ::timeout)))
          (is (= ::waiting (deref other 100 ::waiting)))
          (deliver finish true)
          (is (= [nil] (deref reader 5000 ::timeout)))
          (is (= true (deref other 5000 ::timeout)))
          (finally (deliver finish true) (future-cancel other))))
      (finally
        (deliver finish true)
        (deref reader 5000 ::timeout)
        (future-cancel reader)
        (connection/close connection)))))

(deftest ^:integration subscription-wakes-on-eviction-without-holding-capacity
  (let [connection (new-connection)]
    (try
      (ffi/with-change-notifications [await-change (connection/subscribe connection {:database-id "a"})]
        (is (use-database connection "b"))
        (let [waiting (future (await-change {:timeout-ms 10000}))]
          (try
            (is (nil? (deref waiting 1000 ::timeout)))
            (is (use-database connection "a"))
            (finally (future-cancel waiting)))))
      (finally (connection/close connection)))))

(deftest ^:integration invalid-handles-and-callback-errors-release-capacity
  (let [connection (new-connection)]
    (try
      (is (thrown-with-msg? Exception #"callback"
            (connection/with-database [_ connection {:database-id "a"}]
              (throw (ex-info "callback" {})))))
      (connection/with-database [database connection {:database-id "a"}]
        (ffi/await (ffi/close-database! database)))
      (is (use-database connection "a"))
      (is (use-database connection "b"))
      (finally (connection/close connection)))))

(deftest ^:integration close-rejects-new-borrows-without-waiting-for-active-borrow
  (let [connection (new-connection)]
    (try
      (connection/with-database [database connection {:database-id "a"}]
        (is (nil? (connection/close connection)))
        (is (ffi/usable-database? database))
        (is (thrown? IllegalStateException (use-database connection "b"))))
      (is (thrown? IllegalStateException (use-database connection "a")))
      (finally (connection/close connection)))))

(deftest ^:integration concurrent-first-access-serializes-all-borrows
  (let [connection (new-connection)
        start (promise)
        active (atom 0)
        readers (mapv (fn [_]
                        (future
                          @start
                          (connection/with-database [database connection {:database-id "a"}]
                            (try
                              (is (= 1 (swap! active inc)))
                              (ffi/usable-database? database)
                              (finally (swap! active dec))))))
                      (range 8))]
    (try
      (deliver start true)
      (doseq [reader readers] (is (= true (deref reader 5000 ::timeout))))
      (finally
        (deliver start true)
        (doseq [reader readers] (future-cancel reader))
        (connection/close connection)))))

(deftest ^:integration interrupted-capacity-wait-does-not-block-later-acquisition
  (let [connection (new-connection)
        ready (promise)
        finish (promise)
        interrupted (promise)
        reader (future
                 (connection/with-database [_ connection {:database-id "a"}]
                   (deliver ready true)
                   @finish))]
    (try
      (is (= true (deref ready 5000 ::timeout)))
      (let [waiter (future
                     (try (use-database connection "b")
                          (catch InterruptedException _ (deliver interrupted true))))]
        (try
          (is (= ::waiting (deref waiter 100 ::waiting)))
          (future-cancel waiter)
          (is (= true (deref interrupted 5000 ::timeout)))
          (deliver finish true)
          (is (= true (deref reader 5000 ::timeout)))
          (is (use-database connection "b"))
          (finally (future-cancel waiter))))
      (finally
        (deliver finish true)
        (deref reader 5000 ::timeout)
        (future-cancel reader)
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
      (is (use-database connection "a"))
      (is (use-database connection "b"))
      (finally (connection/close connection)))))

(deftest ^:integration failed-open-does-not-leave-a-capacity-reservation
  (let [connection (new-connection)]
    (try
      (is (thrown? Exception (use-database connection nil)))
      (is (use-database connection "a"))
      (is (thrown? Exception (use-database connection nil)))
      (is (use-database connection "b"))
      (finally (connection/close connection)))))

(deftest ^:integration interrupted-first-acquisition-does-not-cancel-lifecycle-work
  (let [connection (new-connection)
        request (future
                  (.interrupt (Thread/currentThread))
                  (try
                    (connection/with-database [_ connection {:database-id "a"}]
                      true)
                    (catch InterruptedException _ ::interrupted)))]
    (try
      (is (= ::interrupted (deref request 5000 ::timeout)))
      (is (use-database connection "a"))
      (is (use-database connection "b"))
      (finally
        (future-cancel request)
        (connection/close connection)))))

(deftest ^:integration interrupted-writer-does-not-let-its-successor-overtake
  (let [connection (new-connection)
        ready (promise)
        finish (promise)
        first-writer (future
                       (connection/with-database [_ connection {:database-id "a"}]
                         (deliver ready true)
                         @finish))
        attempting (promise)
        interrupted (promise)
        entered (promise)]
    (try
      (is (= true (deref ready 5000 ::timeout)))
      (let [middle-writer (future
                            (deliver attempting true)
                            (try
                              (connection/with-database [_ connection {:database-id "a"}]
                                (throw (AssertionError. "Interrupted writer entered its scope")))
                              (catch InterruptedException _ (deliver interrupted true))))]
        (try
          (is (= true (deref attempting 5000 ::timeout)))
          (is (= ::waiting (deref middle-writer 100 ::waiting)))
          (future-cancel middle-writer)
          (is (= true (deref interrupted 5000 ::timeout)))
          (let [last-writer (future
                              (connection/with-database [_ connection {:database-id "a"}]
                                (deliver entered true)))]
            (try
              (is (= ::waiting (deref entered 100 ::waiting)))
              (deliver finish true)
              (is (= true (deref first-writer 5000 ::timeout)))
              (is (= true (deref entered 5000 ::timeout)))
              (deref last-writer 5000 ::timeout)
              (finally (deliver finish true) (future-cancel last-writer))))
          (finally (future-cancel middle-writer))))
      (finally
        (deliver finish true)
        (try (deref first-writer 5000 ::timeout)
             (finally
               (future-cancel first-writer)
               (connection/close connection)))))))

(deftest ^:integration competing-databases-progress-with-a-single-open-slot
  (let [connection (new-connection)
        start (promise)
        readers (mapv (fn [id]
                        (future
                          @start
                          (vec (repeatedly 3 #(use-database connection id)))))
                      ["a" "a" "b" "b"])]
    (try
      (deliver start true)
      (doseq [reader readers]
        (is (= [true true true] (deref reader 15000 ::timeout))))
      (finally
        (deliver start true)
        (try
          (doseq [reader readers] (future-cancel reader))
          (finally (connection/close connection)))))))

(deftest ^:integration subscription-observes-publish-before-waiting
  (let [connection (new-connection)]
    (try
      (ffi/with-change-notifications [await-change (connection/subscribe connection {:database-id "a"})]
        (connection/with-database [database connection {:database-id "a"}]
          (ffi/with-transaction [transaction database]
            (ffi/await (ffi/write-values transaction [[(byte-array [1]) (byte-array [2])]]))
            (ffi/await (ffi/commit-transaction transaction))))
        (let [waiting (future (await-change {:timeout-ms 10000}))]
          (try
            (is (nil? (deref waiting 1000 ::timeout)))
            (finally (future-cancel waiting)))))
      (finally (connection/close connection)))))
