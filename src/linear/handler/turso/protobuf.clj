(ns linear.handler.turso.protobuf
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.spec :as spec]
   [linear.usecase.database.revisions :as revisions]
   [ring.core.protocols :as ring])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream DataInputStream OutputStream)
   (java.nio.charset StandardCharsets)
   (org.roaringbitmap RoaringBitmap)))

(defn- malformed [message]
  (throw (ex-info message {::anomaly/category ::anomaly/incorrect
                           ::anomaly/message message
                           :reason ::malformed-protobuf})))

(defn- byte-at [^bytes data {:keys [index]}]
  (bit-and (aget data index) 0xff))

(defn- read-varint [^bytes data {:keys [offset]}]
  (loop [index offset
         shift 0
         value 0]
    (when (>= index (alength data))
      (malformed "Truncated protobuf varint"))
    (let [byte (byte-at data {:index index})
          value (bit-or value (bit-shift-left (bit-and byte 0x7f) shift))]
      (if (zero? (bit-and byte 0x80))
        [value (inc index)]
        (if (< shift 63)
          (recur (inc index) (+ shift 7) value)
          (malformed "Protobuf varint is too large"))))))

(defn- read-length-delimited [^bytes data {:keys [offset]}]
  (let [[length next-index] (read-varint data {:offset offset})
        length              (long length)
        next-index          (long next-index)
        end                 (+ next-index length)]
    (when (or (neg? length) (> end (alength data)))
      (malformed "Truncated protobuf length-delimited field"))
    [(java.util.Arrays/copyOfRange data (int next-index) (int end)) end]))

(defn- utf8 [^bytes data]
  (String. data StandardCharsets/UTF_8))

(defn- skip-field [^bytes data {:keys [wire-type offset]}]
  (case (int wire-type)
    0 (second (read-varint data {:offset offset}))
    1 (let [end (+ offset 8)]
        (if (<= end (alength data))
          end
          (malformed "Truncated protobuf fixed64 field")))
    2 (second (read-length-delimited data {:offset offset}))
    5 (let [end (+ offset 4)]
        (if (<= end (alength data))
          end
          (malformed "Truncated protobuf fixed32 field")))
    (malformed "Unsupported protobuf wire type")))

(defn decode-pull
  "Parse pull wire fields without applying protocol semantics."
  [^bytes data]
  (loop [offset (long 0)
         pull {}]
    (if (= offset (alength data))
      pull
      (let [[key next-offset] (read-varint data {:offset offset})
            field-number     (unsigned-bit-shift-right key 3)
            wire-type        (bit-and key 0x07)]
        (when (zero? field-number)
          (malformed "Protobuf field number must not be zero"))
        (case (int field-number)
          1 (if (= 0 wire-type)
              (let [[value end] (read-varint data {:offset next-offset})]
                (recur (long end) (assoc pull :encoding value)))
              (malformed "Pull encoding has an invalid wire type"))
          2 (if (= 2 wire-type)
              (let [[value end] (read-length-delimited data {:offset next-offset})]
                (recur (long end) (assoc pull :server-revision (utf8 value))))
              (malformed "Pull server revision has an invalid wire type"))
          3 (if (= 2 wire-type)
              (let [[value end] (read-length-delimited data {:offset next-offset})]
                (recur (long end) (assoc pull :client-revision (utf8 value))))
              (malformed "Pull client revision has an invalid wire type"))
          4 (if (= 0 wire-type)
              (let [[value end] (read-varint data {:offset next-offset})]
                (when-not (<= 0 value 0xffffffff)
                  (malformed "Pull long poll timeout must be a uint32"))
                (recur (long end) (assoc pull :long-poll-timeout-ms value)))
              (malformed "Pull long poll timeout has an invalid wire type"))
          5 (if (= 2 wire-type)
              (let [[value end] (read-length-delimited data {:offset next-offset})]
                (recur (long end) (assoc pull :server-pages-selector value)))
              (malformed "Pull page selector has an invalid wire type"))
          6 (if (= 2 wire-type)
              (let [[value end] (read-length-delimited data {:offset next-offset})]
                (recur (long end) (assoc pull :client-pages value)))
              (malformed "Pull client pages has an invalid wire type"))
          7 (if (= 2 wire-type)
              (let [[value end] (read-length-delimited data {:offset next-offset})]
                (recur (long end) (assoc pull :server-query-selector (utf8 value))))
              (malformed "Pull query selector has an invalid wire type"))
          8 (if (= 0 wire-type)
              (let [[value end] (read-varint data {:offset next-offset})]
                (recur (long end) (assoc pull :stream-kind value)))
              (malformed "Pull stream kind has an invalid wire type"))
          (recur (long (skip-field data {:wire-type wire-type
                                         :offset next-offset}))
            pull))))))

(defn decode-page-selector
  "Decode the protocol's zero-based RoaringBitmap page selector into domain IDs."
  [^bytes data]
  (if (or (nil? data) (zero? (alength data)))
    nil
    (try
      (let [bitmap (RoaringBitmap.)]
        (.deserialize bitmap
                      (DataInputStream.
                        (ByteArrayInputStream. data)))
        (loop [iterator (.getIntIterator bitmap)
               page-ids #{}]
          (if (.hasNext iterator)
            (recur iterator (conj page-ids (inc (.next iterator))))
            page-ids)))
      (catch Exception error
        (throw (ex-info "Pull page selector is malformed"
                        {::anomaly/category ::anomaly/incorrect
                         ::anomaly/message "Pull page selector is malformed"
                         :reason ::malformed-page-selector}
                        error))))))

(defn- write-varint! [^OutputStream output {:keys [value]}]
  (loop [value (long value)]
    (if (< value 0x80)
      (.write output (int value))
      (do
        (.write output (bit-or (bit-and value 0x7f) 0x80))
        (recur (unsigned-bit-shift-right value 7))))))

(defn- write-field-key! [^OutputStream output {:keys [field-number wire-type]}]
  (write-varint! output {:value (bit-or (bit-shift-left field-number 3) wire-type)}))

(defn- write-bytes-field! [^OutputStream output {:keys [field-number ^bytes value]}]
  (write-field-key! output {:field-number field-number
                            :wire-type 2})
  (write-varint! output {:value (alength value)})
  (.write output value 0 (alength value)))

(defn- encoded [{writer :f}]
  (let [output (ByteArrayOutputStream.)]
    (writer output)
    (.toByteArray output)))

(defn- encode-pull-header [{:keys [server-revision database-page-count]}]
  (encoded {:f (fn [output]
                 (write-bytes-field! output {:field-number 1
                                             :value (.getBytes ^String server-revision StandardCharsets/UTF_8)})
                 (write-field-key! output {:field-number 2
                                           :wire-type 0})
                 (write-varint! output {:value database-page-count})
                 (write-bytes-field! output {:field-number 3
                                             :value (byte-array 0)})
                 ;; Explicitly advertise the page protocol. Zero-valued enum fields are
                 ;; written as well so clients do not need to infer the response mode.
                 (write-field-key! output {:field-number 5
                                           :wire-type 0})
                 (write-varint! output {:value 0})
                 (write-field-key! output {:field-number 6
                                           :wire-type 0})
                 (write-varint! output {:value 0})
                 (write-field-key! output {:field-number 8
                                           :wire-type 0})
                 (write-varint! output {:value 1}))}))

(defn- sync-page-id [page-id]
  (if (spec/valid? ::revisions/page-id page-id)
    (dec page-id)
    (throw (ex-info "Invalid domain page ID"
                    {::anomaly/category ::anomaly/fault
                     ::anomaly/message "Invalid domain page ID"
                     :reason ::invalid-page-id
                     :page-id page-id}))))

(defn- encode-page [{:keys [page-id page]}]
  (encoded {:f (fn [output]
                 (write-field-key! output {:field-number 1
                                           :wire-type 0})
                 (write-varint! output {:value (sync-page-id page-id)})
                 (write-bytes-field! output {:field-number 2
                                             :value page}))}))

(defn- write-delimited! [^OutputStream output {:keys [^bytes message]}]
  (write-varint! output {:value (alength message)})
  (.write output message 0 (alength message)))

(defn pull-stream [pull]
  (reify
    ring/StreamableResponseBody
    (write-body-to-stream [_ _ output]
      (write-delimited! output {:message (encode-pull-header pull)})
      (doseq [[page-id page] (sort-by key (:pages pull))]
        (write-delimited! output {:message (encode-page {:page-id page-id
                                                         :page page})})))))
