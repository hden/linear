(ns linear.adapter.slatedb.snapshot-test
  (:require
   [clojure.test :refer [deftest is]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.crypto.core :as crypto-core]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.snapshot :as snapshot]
   [linear.test :refer [catch-ex-data]]
   [linear.usecase.database.revisions :as revisions]))

(defn- head [revision-id size]
  {:revision-id revision-id :database-page-count size :history-expires-at Long/MAX_VALUE})

(defn- view [{:keys [head history pages]}]
  (let [keychain (crypto-core/new-keychain)
        records (into {(seq (key/head)) (codec/encode-head head)}
                      (concat
                        (map (fn [[revision-id undo]]
                               (let [record-key (key/revision revision-id)]
                                 [(seq record-key) (codec/encode-revision keychain {:record-key record-key :revision undo})]))
                             history)
                        (map (fn [[page-id page]]
                               (let [record-key (key/page page-id)]
                                 [(seq record-key) (codec/encode-page keychain {:record-key record-key :page page})]))
                             pages)))]
    (snapshot/consistent-view {:read-values (fn [record-keys] (mapv #(get records (seq %)) record-keys))
                               :keychain keychain})))

(defn- page-values [snapshot ids]
  (update-vals (revisions/fetch-pages-by-ids snapshot {:ids ids}) seq))

(deftest head-does-not-depend-on-history-and-hides-physical-tail-pages
  (let [view (view {:head (head "r-head" 1)
                    :pages {1 (byte-array [7]) 2 (byte-array [9])}})
        current (revisions/head view)]
    (is (= "r-head" (revisions/revision-id current)))
    (is (= 1 (revisions/size current)))
    (is (= {1 '(7) 2 nil} (page-values current #{1 2})))
    (is (= {1 '(7)} (page-values (revisions/as-of view "r-head") #{1})))
    (is (= #{} (revisions/changes-since view {:target-revision-id "r-head" :client-revision-id "r-head"})))))

(deftest historical-reads-apply-every-newer-undo-and-stop-before-the-target
  (let [view (view {:head (head "r-third" 2)
                    :pages {1 (byte-array [4]) 2 (byte-array [2])}
                    :history {"r-third" {:parent (head "r-second" 2) :undo-pages {1 (byte-array [3])}}
                              "r-second" {:parent (head "r-first" 2) :undo-pages {1 (byte-array [1])}}
                              "r-first" {:parent (head "r-root" 1) :undo-pages {2 nil}}}})]
    (doseq [[revision-id size expected]
            [["r-third" 2 {1 '(4) 2 '(2)}]
             ["r-second" 2 {1 '(3) 2 '(2)}]
             ["r-first" 2 {1 '(1) 2 '(2)}]
             ["r-root" 1 {1 '(1) 2 nil}]]]
      (let [snapshot (revisions/as-of view revision-id)]
        (is (= size (revisions/size snapshot)))
        (is (= expected (page-values snapshot #{1 2})))
        (is (= (select-keys expected [1]) (page-values snapshot #{1})))))
    (is (= #{1 2} (revisions/changes-since view {:target-revision-id "r-third" :client-revision-id "r-root"})))
    (is (= #{2} (revisions/changes-since view {:target-revision-id "r-first" :client-revision-id "r-root"})))))

(deftest snapshots-reject-missing-in-range-pages
  (let [view (view {:head (head "r-head" 2) :pages {1 (byte-array [1])}})]
    (is (= ::revisions/incomplete-revision
           (:reason (catch-ex-data #(page-values (revisions/head view) #{2})))))))

(deftest revision-chain-cycles-are-rejected
  (let [view (view {:head (head "r-a" 1)
                    :history {"r-a" {:parent (head "r-b" 1) :undo-pages {}}
                              "r-b" {:parent (head "r-a" 1) :undo-pages {}}}})]
    (doseq [operation [#(revisions/as-of view "r-missing")
                       #(revisions/changes-since view {:target-revision-id "r-a" :client-revision-id "r-missing"})]]
      (is (= ::revisions/revision-cycle (:reason (catch-ex-data operation)))))))

(deftest expired-history-and-unexpired-holes-have-distinct-outcomes
  (doseq [[expires-at category reason expired?]
          [[0 ::anomaly/not-found ::revisions/revision-not-found true]
           [Long/MAX_VALUE ::anomaly/fault ::revisions/incomplete-revision false]]]
    (let [view (view {:head (assoc (head "r-head" 1) :history-expires-at expires-at)})
          error (catch-ex-data #(revisions/as-of view "r-old"))]
      (is (= category (::anomaly/category error)))
      (is (= reason (:reason error)))
      (is (= expired? (:expired? error))))))

(deftest missing-targets-and-invalid-cursors-retain-their-domain-reasons
  (let [view (view {:head (head "r-head" 1)
                    :history {"r-head" {:parent nil :undo-pages {1 nil}}}})]
    (is (= ::revisions/revision-not-found
           (:reason (catch-ex-data #(revisions/as-of view "r-missing")))))
    (is (= ::revisions/invalid-revision-cursor
           (:reason (catch-ex-data #(revisions/changes-since view {:target-revision-id "r-head" :client-revision-id "r-missing"}))))))
  (is (= ::revisions/head-not-found
         (:reason (catch-ex-data #(snapshot/head-metadata (constantly [nil])))))))

(deftest unreadable-undo-is-not-treated-as-expired-history
  (let [keychain (crypto-core/new-keychain)
        values {(seq (key/head)) (codec/encode-head (assoc (head "r-head" 1) :history-expires-at 0))
                (seq (key/revision "r-head")) (byte-array [0])}
        view (snapshot/consistent-view {:read-values (fn [record-keys] (mapv #(get values (seq %)) record-keys))
                                        :keychain keychain})]
    (is (= ::codec/unreadable-record
           (:reason (catch-ex-data #(revisions/as-of view "r-old")))))))
