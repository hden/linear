(ns linear.adapter.slatedb.snapshot
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.key :as key]
   [linear.usecase.database :as database])
  (:import
   (java.lang AutoCloseable)))

(defn head-revision-id
  {:malli/schema [:-> [:fn ifn?] ::database/revision-id]}
  [read-values]
  (let [[value] (read-values [(key/head)])
        head (codec/decode-head value)]
    (or (:revision-id head)
        (throw (ex-info "SlateDB HEAD does not exist"
                        {::anomaly/category ::anomaly/not-found
                         :reason ::database/head-not-found})))))

(defn- revision-metadata [read-values keychain revision-id]
  (let [record-key (key/revision revision-id)
        [value] (read-values [record-key])]
    (codec/decode-revision keychain record-key value)))

(defn- pages-at-revision [read-values keychain revision-id page-ids]
  (loop [revision-id revision-id
         page-ids    (set page-ids)
         pages       {}
         seen        #{}]
    (cond
      (empty? page-ids) pages
      (nil? revision-id)
      (throw (ex-info "Revision does not contain page state"
                      {::anomaly/category ::anomaly/fault
                       :reason ::database/incomplete-revision
                       :page-ids page-ids}))
      (contains? seen revision-id)
      (throw (ex-info "Revision chain contains a cycle"
                      {::anomaly/category ::anomaly/fault
                       :reason ::database/revision-cycle
                       :revision-id revision-id}))
      :else
      (if-let [revision (revision-metadata read-values keychain revision-id)]
        (let [found (select-keys (:pages revision) page-ids)]
          (recur (:parent revision)
                 (reduce disj page-ids (keys found))
                 (merge pages found)
                 (conj seen revision-id)))
        (throw (ex-info "SlateDB revision does not exist"
                        {::anomaly/category ::anomaly/not-found
                         :reason ::database/revision-not-found
                         :revision-id revision-id}))))))

(defn- changed-page-ids [read-values keychain target-revision-id client-revision-id]
  (loop [revision-id target-revision-id
         page-ids    #{}
         seen        #{}]
    (if (= client-revision-id revision-id)
      page-ids
      (cond
        (nil? revision-id)
        (throw (ex-info "Client revision is not an ancestor of the target revision"
                        {::anomaly/category ::anomaly/incorrect
                         :reason ::database/invalid-revision-cursor
                         :client-revision-id client-revision-id
                         :target-revision-id target-revision-id}))
        (contains? seen revision-id)
        (throw (ex-info "Revision chain contains a cycle"
                        {::anomaly/category ::anomaly/fault
                         :reason ::database/revision-cycle
                         :revision-id revision-id}))
        :else
        (if-let [revision (revision-metadata read-values keychain revision-id)]
          (recur (:parent revision)
                 (into page-ids (keys (:pages revision)))
                 (conj seen revision-id))
          (throw (ex-info "Client revision is not an ancestor of the target revision"
                          {::anomaly/category ::anomaly/incorrect
                           :reason ::database/invalid-revision-cursor
                           :client-revision-id client-revision-id
                           :target-revision-id target-revision-id})))))))

(defn- current-pages [read-values keychain page-ids]
  (let [page-ids (vec page-ids)
        record-keys (mapv key/page page-ids)
        values (read-values record-keys)]
    (into {}
          (map (fn [page-id record-key value]
                 [page-id (codec/decode-page keychain record-key value)])
               page-ids
               record-keys
               values))))

(defrecord ^:private Snapshot [read-values revision-id keychain fetch-pages close! closed?]
  database/Snapshot
  (-revision-id [_]
    revision-id)
  (-size [_]
    (:database-page-count
      (revision-metadata read-values keychain revision-id)))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (fetch-pages ids))
  AutoCloseable
  (close [_]
    (when (compare-and-set! closed? false true)
      (close!))))

(defn- create-snapshot
  [read-values revision-id keychain fetch-pages close!]
  (->Snapshot read-values revision-id keychain fetch-pages close! (atom false)))

(defn snapshot
  {:malli/schema [:->
                  [:fn ifn?]
                  ::database/revision-id
                  ::database/keychain
                  [:fn ifn?]
                  [:and
                   ::database/snapshot
                   [:fn #(instance? AutoCloseable %)]]]}
  [read-values revision-id keychain close!]
  (create-snapshot read-values
    revision-id
    keychain
    #(current-pages read-values keychain %)
    close!))

(defrecord ^:private ReadSession [read-values keychain close! closed?]
  database/DatabaseReadSession
  (-head [_]
    (let [revision-id (head-revision-id read-values)]
      (create-snapshot read-values
                       revision-id
                       keychain
                       #(current-pages read-values keychain %)
                       (fn []))))
  (-as-of [_ revision-id]
    (when-not (revision-metadata read-values keychain revision-id)
      (throw (ex-info "SlateDB revision does not exist"
                      {::anomaly/category ::anomaly/not-found
                       :reason ::database/revision-not-found
                       :revision-id revision-id})))
    (create-snapshot read-values
                     revision-id
                     keychain
                     #(pages-at-revision read-values keychain revision-id %)
                     (fn [])))
  (-changes-since [_ target-revision-id client-revision-id]
    (changed-page-ids read-values
                      keychain
                      target-revision-id
                      client-revision-id))
  AutoCloseable
  (close [_]
    (when (compare-and-set! closed? false true)
      (close!))))

(defn read-session
  {:malli/schema [:->
                  [:fn ifn?]
                  ::database/keychain
                  [:fn ifn?]
                  [:and
                   ::database/database-read-session
                   [:fn #(instance? AutoCloseable %)]]]}
  [read-values keychain close!]
  (->ReadSession read-values keychain close! (atom false)))
