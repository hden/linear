(ns linear.adapter.slatedb.snapshot-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.slatedb.snapshot :as snapshot]
   [linear.usecase.database.revisions :as revisions]
   [taoensso.tempel :as tempel])
  (:import
   (java.util Arrays UUID)))

(defn- revision-records [keychain]
  (let [revision {:revision-id         "r-01K002"
                  :parent              "r-01K001"
                  :database-page-count 2
                  :pages               {1 (byte-array [2])
                                        2 (byte-array [9])}}
        revision-key (key/revision (:revision-id revision))]
    [[(key/head) (codec/encode-head {:revision-id (:revision-id revision)})]
     [revision-key (codec/encode-revision keychain revision-key revision)]
     [(key/page 1) (codec/encode-page keychain (key/page 1) (get-in revision [:pages 1]))]
     [(key/page 2) (codec/encode-page keychain (key/page 2) (get-in revision [:pages 2]))]]))

(defn- with-seeded-snapshot [f]
  (let [keychain (crypto/keychain (tempel/keychain))
        object-store (ffi/open-object-store "memory:///")
        database (ffi/open-database! object-store (str "d-" (UUID/randomUUID)))]
    (try
      (let [transaction (ffi/await (ffi/begin-transaction database))]
        (try
          (ffi/await (ffi/write-values transaction (revision-records keychain)))
          (ffi/await (ffi/commit-transaction transaction))
          (finally
            (ffi/close-transaction! transaction))))
      (let [snapshot (ffi/await (ffi/open-snapshot database))]
        (try
          (f snapshot keychain)
          (finally
            (ffi/close-snapshot! snapshot))))
      (finally
        (ffi/close-database! database)
        (ffi/close-object-store! object-store)))))

(deftest snapshot-reads-its-revision-metadata-and-pages
  (with-seeded-snapshot
    (fn [raw-snapshot keychain]
      (let [read-values       #(ffi/await (ffi/read-snapshot-values raw-snapshot %))
            database-snapshot (snapshot/snapshot read-values "r-01K002" keychain)
            pages             (revisions/fetch-pages-by-ids database-snapshot
                                {:ids #{1 2 3}})]
        (is (= "r-01K002" (revisions/revision-id database-snapshot)))
        (is (= 2 (revisions/size database-snapshot)))
        (is (Arrays/equals (byte-array [2]) (get pages 1)))
        (is (Arrays/equals (byte-array [9]) (get pages 2)))
        (is (nil? (get pages 3)))))))

(deftest reads-head-revision-id
  (with-seeded-snapshot
    (fn [raw-snapshot _keychain]
      (let [read-values #(ffi/await (ffi/read-snapshot-values raw-snapshot %))]
        (is (= "r-01K002" (snapshot/head-revision-id read-values)))))))

(deftest consistent-view-reconstructs-an-as-of-snapshot-and-changes-since-it
  (let [keychain  (crypto/keychain (tempel/keychain))
        object-store (ffi/open-object-store "memory:///")
        database  (ffi/open-database! object-store (str "d-" (UUID/randomUUID)))
        root      {:revision-id         "r-root"
                   :parent              nil
                   :database-page-count 1
                   :pages               {1 (byte-array [1])}}
        first     {:revision-id         "r-first"
                   :parent              "r-root"
                   :database-page-count 2
                   :pages               {2 (byte-array [2])}}
        second    {:revision-id         "r-second"
                   :parent              "r-first"
                   :database-page-count 2
                   :pages               {1 (byte-array [3])}}
        records   (mapcat (fn [revision]
                            (let [revision-key (key/revision (:revision-id revision))]
                              (concat [[revision-key
                                        (codec/encode-revision keychain revision-key revision)]]
                                      (map (fn [[page-id page]]
                                             (let [page-key (key/page page-id)]
                                               [page-key
                                                (codec/encode-page keychain page-key page)]))
                                           (:pages revision)))))
                          [root first second])]
    (try
      (let [transaction (ffi/await (ffi/begin-transaction database))]
        (try
          (ffi/await
            (ffi/write-values transaction
                              (concat records
                                      [[(key/head)
                                        (codec/encode-head {:revision-id "r-second"})]])))
          (ffi/await (ffi/commit-transaction transaction))
          (finally
            (ffi/close-transaction! transaction))))
      (let [raw-snapshot (ffi/await (ffi/open-snapshot database))]
        (try
          (let [read-values #(ffi/await (ffi/read-snapshot-values raw-snapshot %))
                view        (snapshot/consistent-view read-values keychain)
                as-of       (revisions/as-of view "r-first")
                pages       (revisions/fetch-pages-by-ids as-of {:ids #{1 2}})]
            (is (= "r-first" (revisions/revision-id as-of)))
            (is (= 2 (revisions/size as-of)))
            (is (Arrays/equals (byte-array [1]) (get pages 1)))
            (is (Arrays/equals (byte-array [2]) (get pages 2)))
            (is (= #{1 2}
                   (revisions/changes-since view "r-second" "r-root"))))
          (finally
            (ffi/close-snapshot! raw-snapshot))))
      (finally
        (ffi/close-database! database)
        (ffi/close-object-store! object-store)))))

(deftest as-of-rejects-a-revision-with-an-incomplete-page-state
  (let [keychain    (crypto/keychain (tempel/keychain))
        revision    {:revision-id         "r-incomplete"
                     :parent              nil
                     :database-page-count 2
                     :pages               {1 (byte-array [1])}}
        revision-key (key/revision (:revision-id revision))
        values      {(seq revision-key) (codec/encode-revision keychain revision-key revision)}
        read-values (fn [record-keys]
                      (mapv #(values (seq %)) record-keys))
        view        (snapshot/consistent-view read-values keychain)
        as-of       (revisions/as-of view "r-incomplete")]
    (try
      (revisions/fetch-pages-by-ids as-of {:ids #{2}})
      (is false "as-of must reject incomplete page state")
      (catch clojure.lang.ExceptionInfo error
        (is (= "Revision does not contain page state" (.getMessage error)))))))

(deftest revision-chain-cycles-are-rejected
  (let [keychain     (crypto/keychain (tempel/keychain))
        revisions    [{:revision-id "r-cycle-a" :parent "r-cycle-b"
                       :database-page-count 1 :pages {}}
                      {:revision-id "r-cycle-b" :parent "r-cycle-a"
                       :database-page-count 1 :pages {}}]
        values       (into {}
                           (map (fn [revision]
                                  (let [revision-key (key/revision (:revision-id revision))]
                                    [(seq revision-key)
                                     (codec/encode-revision keychain revision-key revision)]))
                                revisions))
        read-values  (fn [record-keys]
                       (mapv #(values (seq %)) record-keys))
        view         (snapshot/consistent-view read-values keychain)]
    (try
      (revisions/changes-since view "r-cycle-a" "r-missing")
      (is false "revision cycles must be rejected")
      (catch clojure.lang.ExceptionInfo error
        (is (= "Revision chain contains a cycle" (.getMessage error)))))))
