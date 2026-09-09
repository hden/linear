(ns linear.usecase.database.model-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [linear.spec :as spec]
   [linear.usecase.database.evaluator :as evaluator]
   [linear.usecase.database.model :as model]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.keychain :as keychain]))

(def ^:private attributes
  {:id "d-1"
   :display-name "Primary"
   ::label "preserved"
   :vault {:id "v-1"
           :owner nil
           :created java.time.Instant/EPOCH
           :keychain (reify keychain/Keychain
                       (-id [_] "test")
                       (-encrypt [_ _ _] (byte-array 0))
                       (-decrypt [_ _ _] nil))}})

(defn- snapshot [revision-id page-count fetch-pages]
  (reify revisions/Snapshot
    (-revision-id [_] revision-id)
    (-size [_] page-count)
    (-fetch-pages-by-ids [_ options] (fetch-pages options))))

(defn- view [head-snapshot snapshots changed-page-ids]
  (reify revisions/ConsistentView
    (-head [_] head-snapshot)
    (-as-of [_ revision-id] (get snapshots revision-id))
    (-changes-since [_ _ _] changed-page-ids)))

(deftest evaluate-uses-head-and-checks-the-revision-parent
  (doseq [parent ["r-current" "r-other"]]
    (let [evaluated (atom nil)
          head (snapshot "r-current" 1 (constantly {}))
          revision {:revision-id "r-next" :parent parent
                    :database-page-count 1 :pages {}}
          evaluator (reify evaluator/Evaluator
                      (-evaluate [_ input]
                        (reset! evaluated input)
                        revision))
          database (model/database attributes
                                   {::model/consistent-view (view head {} #{})
                                    ::model/evaluator evaluator})
          result (try
                   (model/evaluate database {:statements []})
                   (catch clojure.lang.ExceptionInfo error error))]
      (is (identical? head (:snapshot @evaluated)))
      (is (= {:statements []} (:command @evaluated)))
      (if (= parent "r-current")
        (is (= revision result))
        (is (= {::anomaly/category ::anomaly/fault
                :reason :linear.usecase.database/invalid-revision-parent
                :expected-parent "r-current" :actual-parent "r-other"}
               (ex-data result)))))))

(deftest pull-selects-changed-requested-pages-from-the-target-revision
  (let [fetched (atom nil)
        compared (atom nil)
        page (byte-array [2])
        target (snapshot "r-target" 3 (fn [options]
                                        (reset! fetched options)
                                        {2 page}))
        view (reify revisions/ConsistentView
               (-head [_] (throw (AssertionError. "explicit revision must use as-of")))
               (-as-of [_ revision-id]
                 (is (= "r-target" revision-id))
                 target)
               (-changes-since [_ target-id client-id]
                 (reset! compared [target-id client-id])
                 #{1 2}))
        database (model/database attributes {::model/consistent-view view})]
    (is (= {:server-revision "r-target" :database-page-count 3 :pages {2 page}}
           (model/pull database {:server-revision "r-target"
                                 :client-revision "r-old" :page-ids #{2 3}})))
    (is (= ["r-target" "r-old"] @compared))
    (is (= {:ids #{2}} @fetched))))

(deftest pull-reads-head-and-handles-full-current-and-empty-selections
  (doseq [[options expected] [[{} #{1 2 3}]
                              [{:client-revision "r-head"} #{}]
                              [{:page-ids #{}} #{}]]]
    (let [fetched (atom nil)
          head (snapshot "r-head" 3 (fn [{:keys [ids]}]
                                      (reset! fetched ids)
                                      {}))
          view (reify revisions/ConsistentView
                 (-head [_] head)
                 (-as-of [_ _] (throw (AssertionError. "must use head")))
                 (-changes-since [_ _ _] (throw (AssertionError. "no diff needed"))))]
      (is (= {:server-revision "r-head" :database-page-count 3 :pages {}}
             (model/pull (model/database attributes {::model/consistent-view view}) options)))
      (is (= expected @fetched)))))

(deftest publish-next-uses-only-the-database-writer
  (let [published (atom nil)
        writer (reify revisions/RevisionWritable
                 (-publish-next! [_ revision database]
                   (reset! published [revision database])
                   revision))
        database (model/database attributes {::model/revision-writable writer})
        revision {:revision-id "r-next" :parent "r-current"
                  :database-page-count 1 :pages {}}]
    (is (= revision (model/publish-next! database revision)))
    (is (= revision (first @published)))
    (is (identical? database (second @published)))
    (is (= attributes (dissoc database ::model/revision-writable)))))

(deftest operation-contracts-require-only-the-capabilities-they-use
  (let [view (view (snapshot "r-head" 0 (constantly {})) {} #{})
        evaluator (reify evaluator/Evaluator (-evaluate [_ _] nil))
        writer (reify revisions/RevisionWritable (-publish-next! [_ revision _] revision))]
    (doseq [[operation capabilities]
            [[#'model/evaluate {::model/consistent-view view ::model/evaluator evaluator}]
             [#'model/pull {::model/consistent-view view}]
             [#'model/publish-next! {::model/revision-writable writer}]]]
      (let [input-schema (second (:malli/schema (meta operation)))
            database (model/database attributes capabilities)]
        (testing (str operation)
          (is (spec/valid? input-schema database))
          (is (not (spec/valid? input-schema (assoc database :vault {}))))
          (doseq [capability (keys capabilities)]
            (is (not (spec/valid? input-schema (dissoc database capability))))))))
    (is (not (spec/valid? ::revisions/consistent-view
               (model/database attributes {::model/consistent-view view}))))))

(deftest evaluator-contracts-accept-domain-values-and-reject-invalid-input
  (is (spec/valid? ::evaluator/statement
                   {:sql "UPDATE t SET value = ?"
                    :parameters [nil 1 1.5 "text" (byte-array [1 2])]}))
  (is (not (spec/valid? ::evaluator/statement
             {:sql "UPDATE t SET value = ?"
              :parameters [true]})))
  (is (not (spec/valid? ::evaluator/push-command
             {:statements [{:sql :not-a-string :parameters []}]}))))
