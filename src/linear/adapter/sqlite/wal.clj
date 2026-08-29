(ns linear.adapter.sqlite.wal
  (:refer-clojure :exclude [read sync])
  (:require
   [linear.adapter.sqlite.core :refer [fault]]
   [linear.spec :as spec]
   [linear.usecase.database :as database]))

(def ^:private ^:const wal-header-size 32)
(def ^:private ^:const frame-header-size 24)

(defn- u32 [^bytes bytes offset]
  (bit-or (bit-shift-left (long (bit-and (aget bytes offset) 0xff)) 24)
          (bit-shift-left (long (bit-and (aget bytes (inc offset)) 0xff)) 16)
          (bit-shift-left (long (bit-and (aget bytes (+ offset 2)) 0xff)) 8)
          (long (bit-and (aget bytes (+ offset 3)) 0xff))))

(defn- valid-page-size? [page-size]
  (and (<= 512 page-size 65536)
       (zero? (bit-and page-size (dec page-size)))))

(defn- replace-range [segments write-offset ^bytes bytes]
  (let [write-end (+ write-offset (alength bytes))]
    (vec
      (sort-by :offset
               (conj
                 (reduce (fn [result {:keys [offset segment]}]
                           (let [^bytes segment segment
                                 segment-end (+ offset (alength segment))]
                             (if (or (<= segment-end write-offset)
                                     (>= offset write-end))
                               (conj result {:offset offset :segment segment})
                               (cond-> result
                                 (< offset write-offset)
                                 (conj {:offset offset
                                        :segment (java.util.Arrays/copyOfRange
                                                   segment 0 (int (- write-offset offset)))})
                                 (> segment-end write-end)
                                 (conj {:offset write-end
                                        :segment (java.util.Arrays/copyOfRange
                                                   segment (int (- write-end offset))
                                                   (alength segment))})))))
                   []
                   segments)
                 {:offset write-offset :segment (aclone bytes)})))))

(defn- range-bytes [segments offset length]
  (let [end (+ offset length)]
    (when (neg? offset)
      (fault "WAL offset must not be negative" {:reason ::invalid-offset}))
    (loop [position (long offset)
           output (byte-array length)
           remaining segments]
      (if (= position end)
        output
        (if-let [{segment-offset :offset segment :segment} (first remaining)]
          (let [segment-end (+ segment-offset (alength ^bytes segment))]
            (cond
              (<= segment-end position)
              (recur position output (next remaining))

              (< position segment-offset)
              nil

              :else
              (let [amount (min (- end position) (- segment-end position))]
                (System/arraycopy segment
                                  (int (- position segment-offset))
                                  output
                                  (int (- position offset))
                                  (int amount))
                (recur (long (+ position amount)) output remaining))))
          nil)))))

(defn- parse-header [capture]
  (when-let [header (range-bytes (:segments capture) 0 wal-header-size)]
    (let [magic (u32 header 0)
          value (u32 header 8)
          page-size (if (= value 1) 65536 value)]
      (when-not (contains? #{0x377f0682 0x377f0683} magic)
        (fault "Invalid SQLite WAL magic" {:reason ::invalid-header :magic magic}))
      (when-not (valid-page-size? page-size)
        (fault "Invalid SQLite WAL page size"
               {:reason ::invalid-header :page-size page-size}))
      page-size)))

(defn- parse-frames [capture]
  (if-let [page-size (:page-size capture)]
    (loop [capture capture]
      (let [offset (:next-frame-offset capture)]
        (if-let [header (range-bytes (:segments capture) offset frame-header-size)]
          (if-let [page (range-bytes (:segments capture)
                                     (+ offset frame-header-size)
                                     page-size)]
            (let [page-number (u32 header 0)
                  page-count (u32 header 4)
                  _          (when-not (spec/valid? ::database/page-id page-number)
                               (fault "Invalid SQLite WAL page number"
                                      {:reason      ::invalid-page-number
                                       :page-number page-number}))
                  pages (assoc (:pending-pages capture) page-number page)
                  capture (assoc capture
                                 :next-frame-offset (long (+ offset frame-header-size page-size))
                                 :pending-pages pages)]
              (recur (cond-> capture
                       (pos? page-count)
                       (assoc :candidate {:database-page-count page-count
                                          :pages pages}))))
            capture)
          capture)))
    capture))

(defn- reparse-frames-from [capture frame-offset]
  (let [capture (assoc capture
                       :page-size nil
                       :next-frame-offset frame-offset
                       :pending-pages {}
                       :candidate nil)]
    (if-let [page-size (parse-header capture)]
      (parse-frames (assoc capture :page-size page-size))
      capture)))

(defn new-capture []
  {:segments          []
   :page-size         nil
   :next-frame-offset wal-header-size
   :synced-frame-offset wal-header-size
   :pending-pages     {}
   :candidate         nil})

(defn write [capture {:keys [offset bytes]}]
  (when-not (and (integer? offset) (not (neg? offset)))
    (fault "WAL offset must be a non-negative integer" {:reason ::invalid-offset}))
  (let [parsed-through (:next-frame-offset capture)
        capture (assoc capture :segments (replace-range (:segments capture) offset bytes))]
    (if (< offset parsed-through)
      (reparse-frames-from capture
                           (if (< offset (:synced-frame-offset capture))
                             wal-header-size
                             (:synced-frame-offset capture)))
      (parse-frames
        (if (:page-size capture)
          capture
          (if-let [page-size (parse-header capture)]
            (assoc capture :page-size page-size)
            capture))))))

(defn read [capture offset length]
  (let [bytes (range-bytes (:segments capture) offset length)]
    {:bytes (or bytes (byte-array length))
     :short-read? (nil? bytes)}))

(defn commit [capture]
  (:candidate capture))

(defn size [capture]
  (reduce (fn [maximum {:keys [offset segment]}]
            (max maximum (+ offset (alength ^bytes segment))))
          0
          (:segments capture)))

(defn truncate [capture length]
  (when (neg? length)
    (fault "WAL truncate length must not be negative"
           {:reason ::invalid-truncate-length
            :length length}))
  (if (zero? length)
    (new-capture)
    (let [segments (into []
                         (keep (fn [{:keys [offset segment]}]
                                 (when (< offset length)
                                   {:offset offset
                                    :segment (java.util.Arrays/copyOf
                                               ^bytes segment
                                               (int (min (alength ^bytes segment)
                                                         (- length offset))))})))
                         (:segments capture))
          capture (assoc capture :segments segments)]
      (if (< length (:next-frame-offset capture))
        (reparse-frames-from capture
                             (if (< length (:synced-frame-offset capture))
                               wal-header-size
                               (:synced-frame-offset capture)))
        capture))))

(defn sync [capture]
  [(assoc capture
          :synced-frame-offset (:next-frame-offset capture)
          :pending-pages {}
          :candidate nil)
   (commit capture)])
