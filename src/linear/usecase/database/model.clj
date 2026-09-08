(ns linear.usecase.database.model
  (:require
   [clojure.string :as string]
   [cognitect.anomalies :as anomaly]
   [linear.spec :refer [spec-for]]
   [linear.usecase.database.evaluator :as evaluation]
   [linear.usecase.database.revisions :as revisions]
   [linear.usecase.keychain :as keychain]))

(defmethod spec-for ::database-id [_]
  [:and :string [:fn #(string/starts-with? % "d-")]])

(defmethod spec-for ::database [_]
  [:map
   [:id ::database-id]
   [:display-name :string]
   [:vault [:map
            [:id :string]
            [:owner [:maybe :string]]
            [:created inst?]
            [:keychain ::keychain/keychain]]]])

(defn consistent-view [database]
  (::consistent-view database))

(defn evaluator [database]
  (::evaluator database))

(defn database
  {:malli/schema [:-> ::database :map ::database]}
  [attributes capabilities]
  (merge attributes
         (select-keys capabilities
                      [::consistent-view ::evaluator ::revision-writable])))

(defn evaluate
  {:malli/schema [:->
                  [:and ::database
                   [:map
                    [::consistent-view ::revisions/consistent-view]
                    [::evaluator ::evaluation/evaluator]]]
                  ::evaluation/push-command
                  ::revisions/revision]}
  [database command]
  (let [snapshot (revisions/head (consistent-view database))
        parent (revisions/revision-id snapshot)
        revision (evaluation/evaluate (evaluator database)
                                      {:snapshot snapshot :command command})]
    (when-not (= parent (:parent revision))
      (throw (ex-info "Evaluator revision does not descend from the snapshot"
                      {::anomaly/category ::anomaly/fault
                       :reason :linear.usecase.database/invalid-revision-parent
                       :expected-parent parent
                       :actual-parent (:parent revision)})))
    revision))

(defn pull
  {:malli/schema [:->
                  [:and ::database
                   [:map [::consistent-view ::revisions/consistent-view]]]
                  [:map
                   [:client-revision {:optional true} [:maybe ::revisions/revision-id]]
                   [:server-revision {:optional true} [:maybe ::revisions/revision-id]]
                   [:page-ids {:optional true} [:maybe [:set ::revisions/page-id]]]]
                  [:map
                   [:server-revision ::revisions/revision-id]
                   [:database-page-count nat-int?]
                   [:pages [:map-of ::revisions/page-id [:maybe ::revisions/page]]]]]}
  [database {:keys [client-revision server-revision page-ids]}]
  (let [view (consistent-view database)
        snapshot (if server-revision
                   (revisions/as-of view server-revision)
                   (revisions/head view))
        target-revision (revisions/revision-id snapshot)
        page-count (revisions/size snapshot)
        changed-page-ids (cond
                           (not client-revision) (set (range 1 (inc page-count)))
                           (= client-revision target-revision) #{}
                           :else (revisions/changes-since view target-revision client-revision))
        selected-page-ids (if page-ids
                            (set (filter page-ids changed-page-ids))
                            changed-page-ids)]
    {:server-revision target-revision
     :database-page-count page-count
     :pages (revisions/fetch-pages-by-ids snapshot {:ids selected-page-ids})}))

(defn publish-next!
  {:malli/schema [:->
                  [:and ::database
                   [:map [::revision-writable ::revisions/revision-writable]]]
                  ::revisions/revision
                  ::revisions/revision]}
  [database revision]
  (revisions/publish-next! (::revision-writable database) revision database))
