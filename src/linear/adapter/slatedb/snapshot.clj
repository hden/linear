(ns linear.adapter.slatedb.snapshot
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.key :as key]
   [linear.usecase.database.revisions :as revisions]))

(defn head-metadata [read-values]
  (let [[value] (read-values [(key/head)])
        head (codec/decode-head value)]
    (if (:revision-id head)
      head
      (throw (ex-info "SlateDB HEAD does not exist"
                      {::anomaly/category ::anomaly/not-found
                       :reason ::revisions/head-not-found})))))

(defn- read-undo-record [read-values keychain head]
  (let [revision-id (:revision-id head)
        record-key (key/revision revision-id)
        [value] (read-values [record-key])]
    (or (codec/decode-revision keychain {:record-key record-key :value value})
        (let [expired? (<= (:history-expires-at head) (System/currentTimeMillis))]
          (throw (ex-info "SlateDB revision does not exist"
                          {::anomaly/category (if expired? ::anomaly/not-found ::anomaly/fault)
                           :reason (if expired? ::revisions/revision-not-found ::revisions/incomplete-revision)
                           :expired? expired?
                           :revision-id revision-id}))))))

(defn- ensure-unvisited! [seen revision-id]
  (when (contains? seen revision-id)
    (throw (ex-info "Revision chain contains a cycle"
                    {::anomaly/category ::anomaly/fault
                     :reason ::revisions/revision-cycle
                     :revision-id revision-id}))))

(defn- state-at-revision [read-values keychain head target-revision-id restore-pages?]
  (loop [head head
         pages {}
         seen #{}]
    (cond
      (nil? head)
      (throw (ex-info "SlateDB revision does not exist"
                      {::anomaly/category ::anomaly/not-found
                       :reason ::revisions/revision-not-found
                       :revision-id target-revision-id}))

      (= target-revision-id (:revision-id head))
      {:metadata head :undo-pages pages}

      :else
      (do
        (ensure-unvisited! seen (:revision-id head))
        (let [revision (read-undo-record read-values keychain head)]
          (recur (:parent revision)
                 (if restore-pages? (merge pages (:undo-pages revision)) pages)
                 (conj seen (:revision-id head))))))))

(defn- changed-page-ids [read-values keychain head client-revision-id]
  (loop [head head
         page-ids #{}
         seen #{}]
    (cond
      (nil? head)
      (throw (ex-info "Client revision is not an ancestor of the target revision"
                      {::anomaly/category ::anomaly/incorrect
                       :reason ::revisions/invalid-revision-cursor
                       :client-revision-id client-revision-id}))

      (= client-revision-id (:revision-id head)) page-ids

      :else
      (do
        (ensure-unvisited! seen (:revision-id head))
        (let [revision (read-undo-record read-values keychain head)]
          (recur (:parent revision)
                 (into page-ids (keys (:undo-pages revision)))
                 (conj seen (:revision-id head))))))))

(defn- fetch-pages [read-values keychain {:keys [metadata undo-pages]} page-ids]
  (let [page-count (:database-page-count metadata)
        current-ids (vec (filter #(and (<= % page-count)
                                    (not (contains? undo-pages %)))
                           page-ids))
        record-keys (mapv key/page current-ids)
        values (read-values record-keys)
        current-pages (into {}
                            (map (fn [page-id record-key value]
                                   [page-id (codec/decode-page keychain {:record-key record-key :value value})])
                                 current-ids record-keys values))
        pages (merge current-pages (select-keys undo-pages page-ids))]
    (into {}
          (map (fn [page-id]
                 (let [page (when (<= page-id page-count) (get pages page-id))]
                   (when (and (<= page-id page-count) (nil? page))
                     (throw (ex-info "Revision does not contain page state"
                                     {::anomaly/category ::anomaly/fault
                                      :reason ::revisions/incomplete-revision
                                      :revision-id (:revision-id metadata)
                                      :page-id page-id})))
                   [page-id page])))
          page-ids)))

(defrecord ^:private Snapshot [read-values keychain state]
  revisions/Snapshot
  (-revision-id [_]
    (get-in state [:metadata :revision-id]))
  (-size [_]
    (get-in state [:metadata :database-page-count]))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (fetch-pages read-values keychain state ids)))

(defrecord ^:private ConsistentView [read-values keychain]
  revisions/ConsistentView
  (-head [_]
    (->Snapshot read-values keychain {:metadata (head-metadata read-values) :undo-pages {}}))
  (-as-of [_ revision-id]
    (->Snapshot read-values keychain
                (state-at-revision read-values keychain (head-metadata read-values) revision-id true)))
  (-changes-since [_ target-revision-id client-revision-id]
    (let [state (state-at-revision read-values keychain (head-metadata read-values) target-revision-id false)]
      (changed-page-ids read-values keychain (:metadata state) client-revision-id))))

(defn consistent-view [{:keys [read-values keychain]}]
  (->ConsistentView read-values keychain))
