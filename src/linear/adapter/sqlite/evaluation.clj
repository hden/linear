(ns linear.adapter.sqlite.evaluation
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.adapter.sqlite.file :as file]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.adapter.sqlite.wal :as wal]
   [linear.spec :refer [spec-for]]
   [linear.usecase.database.revisions :as revisions]))

(defrecord WalFile [state]
  sqlite/File
  (-close [_]
    nil)
  (-read [_ offset length]
    (wal/read (:wal @state) offset length))
  (-write [_ offset bytes]
    (swap! state update :wal wal/write {:offset offset :bytes bytes})
    nil)
  (-truncate [_ length]
    (swap! state update :wal wal/truncate length)
    nil)
  (-sync [_]
    (swap! state
           (fn [{:keys [wal] :as current}]
             (let [[capture delta] (wal/sync wal)]
               (cond-> (assoc current :wal capture)
                 delta (update :commits conj delta)))))
    nil)
  (-size [_]
    (wal/size (:wal @state))))

(defrecord Evaluation [path wal-path main-file wal-file state]
  sqlite/FileSystem
  (-open [_ {requested-path :path requested-mode :requested-mode}]
    (cond
      (= requested-path path)
      {:file main-file :mode requested-mode}

      (= requested-path wal-path)
      {:file wal-file :mode requested-mode}

      :else
      (fault "SQLite opened an unsupported evaluation path"
             {:reason ::unsupported-path :path requested-path})))
  (-delete [_ {requested-path :path}]
    (when (= requested-path wal-path)
      (sqlite/truncate wal-file 0))
    nil)
  (-access [_ {requested-path :path}]
    (cond
      (= requested-path path) true
      (= requested-path wal-path) (pos? (sqlite/size wal-file))
      :else false))
  (-full-path [_ {:keys [path]}]
    path))

(defmethod spec-for ::evaluation [_]
  [:fn #(instance? Evaluation %)])

(defn evaluation
  {:malli/schema [:-> ::revisions/snapshot [:map [:path ::sqlite/path]] ::evaluation]}
  [snapshot {:keys [path]}]
  (let [state (atom {:wal (wal/new-capture) :commits []})]
    (->Evaluation path
                  (str path "-wal")
                  (file/snapshot-file snapshot)
                  (->WalFile state)
                  state)))

(defn commit
  {:malli/schema [:-> ::evaluation :map]}
  [evaluation]
  (let [commits (:commits @(:state evaluation))]
    (case (count commits)
      1 (first commits)
      0 (throw (ex-info "SQLite evaluation produced no commit"
                        {::anomaly/category ::anomaly/incorrect
                         :reason            ::missing-commit
                         :wal-size          (sqlite/size (:wal-file evaluation))}))
      (throw (ex-info "SQLite evaluation produced multiple commits"
                      {::anomaly/category ::anomaly/incorrect
                       :reason            ::multiple-commits
                       :count             (count commits)})))))
