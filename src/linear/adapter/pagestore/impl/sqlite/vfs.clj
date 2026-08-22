(ns linear.adapter.pagestore.impl.sqlite.vfs
  "SQLite VFS byte-range operations built from logical pagestore pages."
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.impl.sqlite.snapshot :as snapshot]))

(def ^:private ^:const default-page-size 4096)

(defn- fault [message data]
  (throw (ex-info message (assoc data ::anomaly/category ::anomaly/fault))))

(defn- u16 [^bytes bytes offset]
  (bit-or (bit-shift-left (bit-and (aget bytes offset) 0xff) 8)
          (bit-and (aget bytes (inc offset)) 0xff)))

(defn- valid-page-size? [page-size]
  (and (<= 512 page-size 65536)
       (zero? (bit-and page-size (dec page-size)))))

(defn- sqlite-page-size [database]
  (if (zero? (snapshot/page-count database))
    default-page-size
    (let [header (get (snapshot/fetch-pages database #{1}) 1)]
      (when (or (nil? header) (< (alength ^bytes header) 18))
        (fault "SQLite main database is missing its first-page header"
               {:reason ::missing-header}))
      (let [value     (u16 header 16)
            page-size (if (= value 1) 65536 value)]
        (when-not (valid-page-size? page-size)
          (fault "SQLite main database has an invalid page size"
                 {:reason    ::invalid-page-size
                  :page-size page-size}))
        page-size))))

(defn main-database-size [database]
  (* (snapshot/page-count database) (sqlite-page-size database)))

(defn- checked-request [offset amount]
  (when-not (and (integer? offset) (not (neg? offset)))
    (fault "SQLite VFS read offset must not be negative"
           {:reason ::invalid-offset
            :offset offset}))
  (when-not (and (integer? amount) (not (neg? amount)) (<= amount Integer/MAX_VALUE))
    (fault "SQLite VFS read size must be a non-negative Java array length"
           {:reason ::invalid-read-size
            :amount amount})))

(defn- page-numbers-for-range [offset end page-size]
  (if (<= end offset)
    #{}
    (let [first-page-number (inc (quot offset page-size))
          last-page-number  (inc (quot (dec end) page-size))]
      (into #{} (range first-page-number (inc last-page-number))))))

(defn read-main-database [database offset amount]
  (checked-request offset amount)
  (let [page-size     (sqlite-page-size database)
        file-size     (* (snapshot/page-count database) page-size)
        requested-end (+ offset amount)
        available-end (min requested-end file-size)
        output        (byte-array amount)
        page-numbers  (page-numbers-for-range offset available-end page-size)
        pages         (snapshot/fetch-pages database page-numbers)]
    (doseq [page-number page-numbers]
      (let [^bytes page (get pages page-number)]
        (when (nil? page)
          (fault "SQLite main database is missing a page"
                 {:reason      ::missing-page
                  :page-number page-number}))
        (let [page-start   (* (dec page-number) page-size)
              copy-start   (max offset page-start)
              copy-end     (min available-end (+ page-start page-size))
              copy-length  (- copy-end copy-start)
              source-start (- copy-start page-start)
              output-start (- copy-start offset)]
          (when (pos? copy-length)
            (System/arraycopy page
                              (int source-start)
                              output
                              (int output-start)
                              (int copy-length))))))
    {:bytes       output
     :short-read? (< file-size requested-end)}))
