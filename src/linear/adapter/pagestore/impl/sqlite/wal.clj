(ns linear.adapter.pagestore.impl.sqlite.wal
  (:refer-clojure :exclude [sync])
  (:require
   [cognitect.anomalies :as anomaly]))

(def ^:private ^:const wal-header-size 32)
(def ^:private ^:const frame-header-size 24)

(defn- fault [message data]
  (throw (ex-info message (assoc data ::anomaly/category ::anomaly/fault))))

(defn- u32 [^bytes bytes offset]
  (bit-or (bit-shift-left (long (bit-and (aget bytes offset) 0xff)) 24)
          (bit-shift-left (long (bit-and (aget bytes (inc offset)) 0xff)) 16)
          (bit-shift-left (long (bit-and (aget bytes (+ offset 2)) 0xff)) 8)
          (long (bit-and (aget bytes (+ offset 3)) 0xff))))

(defn- range-bytes [ordered-segments offset length]
  (let [end (+ offset length)]
    (loop [position offset
           output   (byte-array length)
           segments ordered-segments]
      (if (= position end)
        output
        (if-let [{segment-offset :offset segment-bytes :bytes :as segment} (first segments)]
          (let [segment-end (+ segment-offset (alength ^bytes segment-bytes))]
            (cond
              (<= segment-end position)
              (recur position output (next segments))

              (< position segment-offset)
              nil

              :else
              (let [amount (int (min (- end position) (- segment-end position)))]
                (System/arraycopy segment-bytes
                                  (int (- position segment-offset))
                                  output
                                  (int (- position offset))
                                  amount)
                (recur (+ position amount) output (cons segment (next segments))))))
          nil)))))

(defn- replace-segments [segments offset ^bytes bytes copy-bytes?]
  (let [end       (+ offset (alength bytes))
        preserved (mapcat (fn [{segment-offset :offset segment-bytes :bytes :as segment}]
                            (let [^bytes segment-bytes segment-bytes
                                  segment-end (+ segment-offset (alength segment-bytes))]
                              (cond
                                (or (<= segment-end offset) (>= segment-offset end))
                                [segment]

                                :else
                                (cond-> []
                                  (< segment-offset offset)
                                  (conj {:offset segment-offset
                                         :bytes  (java.util.Arrays/copyOfRange
                                                   segment-bytes
                                                   0
                                                   (int (- offset segment-offset)))})

                                  (> segment-end end)
                                  (conj {:offset end
                                         :bytes  (java.util.Arrays/copyOfRange
                                                   segment-bytes
                                                   (int (- end segment-offset))
                                                   (alength segment-bytes))})))))
                          segments)]
    (vec (sort-by :offset
                  (conj (vec preserved)
                        {:offset offset
                         :bytes  (if copy-bytes? (aclone bytes) bytes)})))))

(defn- valid-page-size? [page-size]
  (and (<= 512 page-size 65536)
       (zero? (bit-and page-size (dec page-size)))))

(defn- read-header [capture]
  (when-let [header (range-bytes (:segments capture) 0 wal-header-size)]
    (let [magic     (u32 header 0)
          page-size (let [value (u32 header 8)]
                      (if (= value 1) 65536 value))]
      (when-not (contains? #{0x377f0682 0x377f0683} magic)
        (fault "SQLite WAL header has an invalid magic number"
               {:reason ::invalid-header
                :magic  magic}))
      (when-not (valid-page-size? page-size)
        (fault "SQLite WAL header has an invalid page size"
               {:reason    ::invalid-header
                :page-size page-size}))
      page-size)))

(defn- parse-complete-frames [capture]
  (if-let [page-size (:page-size capture)]
    (loop [capture capture]
      (let [offset       (:next-frame-offset capture)
            frame-header (range-bytes (:segments capture) offset frame-header-size)]
        (if-not frame-header
          capture
          (let [page-number (u32 frame-header 0)
                page-count  (u32 frame-header 4)
                page-bytes  (range-bytes (:segments capture)
                                         (+ offset frame-header-size)
                                         page-size)]
            (if-not page-bytes
              capture
              (let [pages        (assoc (:pending-pages capture) page-number page-bytes)
                    next-capture (assoc capture
                                        :next-frame-offset (+ offset frame-header-size page-size)
                                        :pending-pages pages)]
                (recur (if (pos? page-count)
                         (assoc next-capture
                                :candidate-delta {:pages               pages
                                                  :database-page-count page-count})
                         next-capture))))))))
    capture))

(defn new-capture []
  {:segments          []
   :page-size         nil
   :next-frame-offset wal-header-size
   :pending-pages     {}
   :candidate-delta   nil})

(defn- write* [capture {:keys [offset bytes]} copy-bytes?]
  (when (neg? offset)
    (fault "SQLite WAL write offset must not be negative"
           {:reason ::invalid-offset
            :offset offset}))
  (let [capture (update capture :segments replace-segments offset bytes copy-bytes?)
        capture (if (:page-size capture)
                  capture
                  (if-let [page-size (read-header capture)]
                    (assoc capture :page-size page-size)
                    capture))]
    (parse-complete-frames capture)))

(defn write [capture write]
  (write* capture write true))

(defn write-owned
  "Stores a newly allocated byte array without copying it again.
  The caller must not retain or mutate :bytes after this call."
  [capture write]
  (write* capture write false))

(defn size [capture]
  (reduce (fn [maximum {:keys [offset bytes]}]
            (max maximum (+ offset (alength ^bytes bytes))))
          0
          (:segments capture)))

(defn read-at [capture offset length]
  (when (or (neg? offset) (neg? length))
    (fault "SQLite WAL read range must not be negative"
           {:reason ::invalid-read-range
            :offset offset
            :length length}))
  (let [end   (+ offset length)
        bytes (range-bytes (:segments capture) offset length)]
    {:bytes      (or bytes (byte-array length))
     :short-read? (or (nil? bytes) (< (size capture) end))}))

(defn truncate [capture size]
  (when (neg? size)
    (fault "SQLite WAL truncate size must not be negative"
           {:reason ::invalid-truncate-size
            :size   size}))
  (if (zero? size)
    (new-capture)
    (let [segments (keep (fn [{:keys [offset bytes]}]
                           (let [^bytes segment-bytes bytes]
                             (when (< offset size)
                               {:offset offset
                                :bytes  (java.util.Arrays/copyOf
                                          segment-bytes
                                          (int (min (alength segment-bytes)
                                                    (- size offset))))})))
                         (:segments capture))]
      (parse-complete-frames (assoc capture :segments segments)))))

(defn sync [capture]
  (if-let [delta (:candidate-delta capture)]
    [(assoc capture :pending-pages {} :candidate-delta nil) delta]
    [capture nil]))
