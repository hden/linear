(ns linear.adapter.sqlite.file
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.adapter.sqlite.protocol :as sqlite]
   [linear.protocol :as protocol]))

(defn- page-id [page-number]
  (when-not (pos-int? page-number)
    (throw (ex-info "SQLite page number must be positive"
                    {::anomaly/category ::anomaly/incorrect
                     :reason            ::invalid-page-number
                     :page-number       page-number})))
  (str page-number))

(defn- fetch-pages [snapshot page-numbers]
  (let [page-numbers (set page-numbers)
        pages        (protocol/-fetch-pages-by-ids
                       snapshot
                       {:ids (into #{} (map page-id) page-numbers)})]
    (into {}
          (map (fn [page-number]
                 [page-number (get pages (page-id page-number))]))
          page-numbers)))

(defn- page-size [snapshot]
  (let [header (get (fetch-pages snapshot #{1}) 1)]
    (when-not (and header (>= (alength ^bytes header) 18))
      (fault "SQLite snapshot has no readable page-one header"
             {:reason ::missing-header}))
    (let [value     (bit-or (bit-shift-left (bit-and (aget ^bytes header 16) 0xff) 8)
                            (bit-and (aget ^bytes header 17) 0xff))
          page-size (if (= value 1) 65536 value)]
      (when-not (and (<= 512 page-size 65536)
                     (zero? (bit-and page-size (dec page-size))))
        (fault "SQLite snapshot has an invalid page size"
               {:reason    ::invalid-page-size
                :page-size page-size}))
      page-size)))

(defn- page-numbers-for-range [offset end page-size]
  (if (<= end offset)
    #{}
    (let [first-page (inc (quot offset page-size))
          last-page  (inc (quot (dec end) page-size))]
      (into #{} (range first-page (inc last-page))))))

(defn- read-snapshot [snapshot page-size file-size offset length]
  (let [requested-end (+ offset length)
        available-end (min requested-end file-size)
        output        (byte-array length)
        pages         (fetch-pages snapshot
                                   (page-numbers-for-range offset available-end page-size))]
    (doseq [[page-number page] pages]
      (when-not page
        (fault "SQLite snapshot is missing a page"
               {:reason ::missing-page :page-number page-number}))
      (let [page-start (* (dec page-number) page-size)
            copy-start (max offset page-start)
            copy-end   (min available-end (+ page-start page-size))]
        (when (< copy-start copy-end)
          (System/arraycopy page
                            (int (- copy-start page-start))
                            output
                            (int (- copy-start offset))
                            (int (- copy-end copy-start))))))
    {:bytes       output
     :short-read? (< file-size requested-end)}))

(defrecord SnapshotFile [snapshot page-size file-size]
  sqlite/File
  (-close [_]
    nil)
  (-read [_ offset length]
    (read-snapshot snapshot page-size file-size offset length))
  (-write [_ _offset _bytes]
    (fault "SQLite attempted to mutate an immutable Snapshot"
           {:reason ::immutable-file :operation :write}))
  (-truncate [_ _length]
    (fault "SQLite attempted to mutate an immutable Snapshot"
           {:reason ::immutable-file :operation :truncate}))
  (-sync [_]
    nil)
  (-size [_]
    file-size))

(defn snapshot-file
  {:malli/schema [:-> ::protocol/snapshot ::sqlite/file]}
  [snapshot]
  (let [page-size (page-size snapshot)]
    (->SnapshotFile snapshot page-size (* (protocol/-size snapshot) page-size))))
