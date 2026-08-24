(ns linear.adapter.sqlite.evaluator-test
  (:require
   [clojure.test :refer [deftest is]]
   [integrant.core :as integrant]
   [linear.adapter.sqlite.connection :as connection]
   [linear.adapter.sqlite.evaluation :as evaluation]
   [linear.adapter.sqlite.evaluator]
   [linear.adapter.sqlite.vfs :as vfs]
   [linear.protocol :as protocol]))

(defrecord Snapshot []
  protocol/Snapshot
  (-revision-id [_] "r0")
  (-size [_] 0)
  (-fetch-pages-by-ids [_ _] {}))

(deftest integrant-is-the-public-evaluator-construction-boundary
  (let [publics (ns-publics 'linear.adapter.sqlite.evaluator)
        events  (atom [])]
    (with-redefs [vfs/install (fn [options]
                                (swap! events conj [:install options])
                                ::resources)
                  vfs/uninstall (fn [resources]
                                  (swap! events conj [:uninstall resources])
                                  nil)
                  vfs/drain! (fn [resources]
                               (swap! events conj [:drain resources])
                               nil)]
      (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator
                                          {:library "test"})]
        (is (not (contains? publics '->Evaluator)))
        (is (not (contains? publics 'map->Evaluator)))
        (is (satisfies? protocol/Evaluator evaluator))
        (is (protocol/ready? evaluator))
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)
        (is (false? (protocol/ready? evaluator)))
        (is (= [[:install {:library "test" :name "linear-sqlite"}]
                [:drain ::resources]
                [:uninstall ::resources]]
               @events))))))

(deftest evaluation-orchestrates-sqlite-and-cleans-up-in-order
  (let [events (atom [])]
    (with-redefs [vfs/install (fn [_] {:name "test-vfs"})
                  vfs/mount (fn [_ request]
                              (swap! events conj [:vfs-mount request])
                              ::invocation)
                  vfs/throw-if-failed! (fn [_] nil)
                  vfs/unmount (fn [_]
                                (swap! events conj [:vfs-unmount])
                                nil)
                  connection/open (fn [options]
                                    (swap! events conj [:connection-open options])
                                    ::connection)
                  connection/execute (fn [_ sql]
                                       (swap! events conj [:execute sql])
                                       nil)
                  connection/close (fn [_]
                                     (swap! events conj [:connection-close])
                                     nil)
                  evaluation/evaluation (fn [_ path]
                                          (swap! events conj [:evaluation path])
                                          ::filesystem)
                  evaluation/commit (fn [_]
                                      (swap! events conj [:delta])
                                      {:database-page-count 1})]
      (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator
                                          {:library "test" :name "test-vfs"})
            result    (protocol/evaluate evaluator
                                         {:snapshot (->Snapshot)
                                          :operation (fn [execute]
                                                       (execute "UPDATE example"))})
            path      (second (first @events))]
        (is (= "r0" (:parent result)))
        (is (= [[:evaluation path]
                [:vfs-mount {:path path :filesystem ::filesystem}]
                [:connection-open {:path path :vfs-name "test-vfs"}]
                [:execute "PRAGMA locking_mode=EXCLUSIVE"]
                [:execute "PRAGMA wal_autocheckpoint=0"]
                [:execute "PRAGMA temp_store=MEMORY"]
                [:execute "PRAGMA synchronous=FULL"]
                [:execute "BEGIN IMMEDIATE"]
                [:execute "UPDATE example"]
                [:execute "COMMIT"]
                [:delta]
                [:connection-close]
                [:vfs-unmount]]
               @events))))))

(deftest callback-failure-wins-when-the-native-call-also-throws
  (let [callback-cause (ex-info "callback failed" {:reason ::callback-failed})
        failure (try
                  (with-redefs [vfs/throw-if-failed! (fn [_]
                                                       (throw callback-cause))]
                    (#'linear.adapter.sqlite.evaluator/call-sqlite
                      ::invocation
                      {:operation :execute}
                      #(throw (ex-info "native failed" {}))))
                  nil
                  (catch clojure.lang.ExceptionInfo exception
                    exception))]
    (is (identical? callback-cause (.getCause failure)))))

(deftest shutdown-timeout-abandons-the-installed-vfs
  (let [events (atom [])]
    (with-redefs [vfs/install (fn [_] ::resources)
                  vfs/drain! (fn [_]
                               (swap! events conj :drain)
                               nil)
                  vfs/uninstall (fn [_]
                                  (swap! events conj :uninstall)
                                  nil)
                  vfs/ok? (fn [_] true)]
      (let [evaluator (integrant/init-key :linear.adapter.sqlite.evaluator/evaluator
                                          {:library "test" :shutdown-timeout-ms 1})]
        (swap! (:state evaluator) assoc :active 1)
        (integrant/halt-key! :linear.adapter.sqlite.evaluator/evaluator evaluator)
        (is (= :abandoned (:status @(:state evaluator))))
        (is (false? (protocol/ok? evaluator)))
        (is (empty? @events))))))
