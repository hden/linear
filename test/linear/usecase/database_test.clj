(ns linear.usecase.database-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [linear.usecase.database :as database]
   [malli.core :as m])
  (:import
   (java.lang AutoCloseable)))

(defn- snapshot [revision-id events]
  (reify
    AutoCloseable
    (close [_]
      (swap! events conj :snapshot-close))
    database/Snapshot
    (-revision-id [_]
      revision-id)
    (-size [_]
      1)
    (-fetch-pages-by-ids [_ _]
      {1 (byte-array [1])})))

(defn- conflict []
  (ex-info "Revision conflict"
           {::anomaly/category ::anomaly/conflict
            :reason ::database/revision-conflict}))

(deftest push-evaluates-a-current-snapshot-before-publishing
  (let [events   (atom [])
        revision {:revision-id "r-next"
                  :parent "r-current"
                  :database-page-count 1
                  :pages {1 (byte-array [2])}}
        source   (reify database/SnapshotReader
                   (-latest-snapshot [_ _]
                     (swap! events conj :snapshot-open)
                     (snapshot "r-current" events)))
        evaluator (reify database/Evaluator
                    (-evaluate [_ {:keys [snapshot command]}]
                      (swap! events conj [:evaluate
                                          (database/revision-id snapshot)
                                          command])
                      revision))
        publisher (reify database/RevisionWriter
                    (-publish-next-revision! [_ _ published]
                      (swap! events conj [:publish published])
                      published))
        command  {:statements [{:sql "UPDATE t SET value = ?"
                                :parameters ["next"]}]}
        result   (database/push! {:snapshot-reader source
                                  :revision-writer publisher
                                  :evaluator evaluator}
                                 {:id "d-1"
                                  :display-name "Primary"
                                  :keychain ::keychain}
                                 command)]
    (is (= revision result))
    (is (= [:snapshot-open
            [:evaluate "r-current" command]
            :snapshot-close
            [:publish revision]]
           @events))))

(deftest revision-conflict-repeats-snapshot-and-evaluation
  (let [snapshot-ids (atom ["r-first" "r-second"])
        evaluated    (atom [])
        publishes    (atom 0)
        source       (reify database/SnapshotReader
                       (-latest-snapshot [_ _]
                         (let [revision-id (first @snapshot-ids)]
                           (swap! snapshot-ids rest)
                           (snapshot revision-id (atom [])))))
        evaluator    (reify database/Evaluator
                       (-evaluate [_ {:keys [snapshot]}]
                         (let [parent (database/revision-id snapshot)
                               revision {:revision-id (str parent "-next")
                                         :parent parent
                                         :database-page-count 1
                                         :pages {}}]
                           (swap! evaluated conj parent)
                           revision)))
        publisher    (reify database/RevisionWriter
                       (-publish-next-revision! [_ _ revision]
                         (if (= 1 (swap! publishes inc))
                           (throw (conflict))
                           revision)))
        result       (database/push! {:snapshot-reader source
                                      :revision-writer publisher
                                      :evaluator evaluator}
                                     {:id "d-1"
                                      :display-name "Primary"
                                      :keychain ::keychain}
                                     {:statements []})]
    (is (= "r-second-next" (:revision-id result)))
    (is (= ["r-first" "r-second"] @evaluated))
    (is (= 2 @publishes))))

(deftest push-does-not-retry-non-conflict-failures
  (let [attempts  (atom 0)
        failure   (ex-info "Unavailable" {::anomaly/category ::anomaly/unavailable})
        source    (reify database/SnapshotReader
                    (-latest-snapshot [_ _]
                      (swap! attempts inc)
                      (throw failure)))
        evaluator (reify database/Evaluator
                    (-evaluate [_ _]
                      (throw (AssertionError. "must not evaluate"))))
        publisher (reify database/RevisionWriter
                    (-publish-next-revision! [_ _ _]
                      (throw (AssertionError. "must not publish"))))]
    (is (identical?
          failure
          (try
            (database/push! {:snapshot-reader source
                             :revision-writer publisher
                             :evaluator evaluator}
                            {:id "d-1"
                             :display-name "Primary"
                             :keychain ::keychain}
                            {:statements []})
            (catch clojure.lang.ExceptionInfo error
              error))))
    (is (= 1 @attempts))))

(deftest push-reports-exhausted-revision-conflicts
  (let [attempts  (atom 0)
        source    (reify database/SnapshotReader
                    (-latest-snapshot [_ _]
                      (swap! attempts inc)
                      (snapshot "r-current" (atom []))))
        evaluator (reify database/Evaluator
                    (-evaluate [_ _]
                      {:revision-id "r-next"
                       :parent "r-current"
                       :database-page-count 1
                       :pages {}}))
        publisher (reify database/RevisionWriter
                    (-publish-next-revision! [_ _ _]
                      (throw (conflict))))
        error     (try
                    (database/push! {:snapshot-reader source
                                     :revision-writer publisher
                                     :evaluator evaluator}
                                    {:id "d-1"
                                     :display-name "Primary"
                                     :keychain ::keychain}
                                    {:statements []})
                    (catch clojure.lang.ExceptionInfo failure
                      failure))]
    (is (= ::anomaly/conflict (-> error ex-data ::anomaly/category)))
    (is (= ::database/push-conflict (-> error ex-data :reason)))
    (is (= 3 (-> error ex-data :attempts)))
    (is (= ::database/revision-conflict (-> error ex-cause ex-data :reason)))
    (is (= 3 @attempts))))

(deftest push-rejects-a-revision-that-does-not-descend-from-the-snapshot
  (let [published? (atom false)
        source     (reify database/SnapshotReader
                     (-latest-snapshot [_ _]
                       (snapshot "r-current" (atom []))))
        evaluator  (reify database/Evaluator
                     (-evaluate [_ _]
                       {:revision-id "r-next"
                        :parent "r-stale"
                        :database-page-count 1
                        :pages {}}))
        publisher  (reify database/RevisionWriter
                     (-publish-next-revision! [_ _ revision]
                       (reset! published? true)
                       revision))
        error      (try
                     (database/push! {:snapshot-reader source
                                      :revision-writer publisher
                                      :evaluator evaluator}
                                     {:id "d-1"
                                      :display-name "Primary"
                                      :keychain ::keychain}
                                     {:statements []})
                     (catch clojure.lang.ExceptionInfo failure
                       failure))]
    (is (= ::anomaly/fault (-> error ex-data ::anomaly/category)))
    (is (= ::database/invalid-revision-parent (-> error ex-data :reason)))
    (is (false? @published?))))

(deftest root-revisions-use-a-nil-parent-and-may-be-empty
  (testing "the domain schema accepts the database creation seed"
    (is (m/validate
          ::database/revision
          {:revision-id "r-root"
           :parent nil
           :database-page-count 0
           :pages {}}))))
