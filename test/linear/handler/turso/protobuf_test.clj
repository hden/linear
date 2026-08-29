(ns linear.handler.turso.protobuf-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [linear.handler.turso.protobuf :as protobuf]
   [linear.test :as test]
   [ring.core.protocols :as ring])
  (:import
   (java.io ByteArrayOutputStream DataOutputStream)
   (java.util Arrays)
   (org.roaringbitmap RoaringBitmap)))

(defn- byte-array-from [& values]
  (byte-array values))

(deftest parses-pull-wire-fields-without-semantic-validation
  (let [pull (protobuf/decode-pull
               (byte-array-from 0x1a 0x08
                 (int \r) (int \-) (int \c) (int \l)
                 (int \i) (int \e) (int \n) (int \t)
                 0x20 0x64
                 0x2a 0x02 0x01 0x02
                 0x48 0x01))]
    (is (= "r-client" (:client-revision pull)))
    (is (= 100 (:long-poll-timeout-ms pull)))
    (is (Arrays/equals
          (byte-array-from 1 2)
          (:server-pages-selector pull)))))

(deftest parses-all-supported-pull-wire-fields
  (let [pull (protobuf/decode-pull
               (byte-array-from
                 0x08 0x01
                 0x12 0x08
                 (int \r) (int \-) (int \s) (int \e)
                 (int \r) (int \v) (int \e) (int \r)
                 0x1a 0x08
                 (int \r) (int \-) (int \c) (int \l)
                 (int \i) (int \e) (int \n) (int \t)
                 0x20 0x64
                 0x2a 0x02 0x01 0x02
                 0x32 0x02 0x03 0x04
                 0x3a 0x05
                 (int \q) (int \u) (int \e) (int \r) (int \y)
                 0x40 0x02))]
    (is (= {:encoding 1
            :server-revision "r-server"
            :client-revision "r-client"
            :long-poll-timeout-ms 100
            :server-query-selector "query"
            :stream-kind 2}
           (dissoc pull :server-pages-selector :client-pages)))
    (is (Arrays/equals (byte-array-from 1 2)
                       (:server-pages-selector pull)))
    (is (Arrays/equals (byte-array-from 3 4)
                       (:client-pages pull)))))

(deftest streams-a-length-prefixed-pull-header-and-pages
  (let [body (protobuf/pull-stream
               {:server-revision "r-current"
                :database-page-count 1
                :pages {1 (byte-array-from 1 2)}})
        output (ByteArrayOutputStream.)]
    (ring/write-body-to-stream body nil output)
    (is (= (seq (byte-array-from 0x15
                  0x0a 0x09
                  (int \r) (int \-) (int \c) (int \u) (int \r)
                  (int \r) (int \e) (int \n) (int \t)
                  0x10 0x01
                  0x1a 0x00
                  0x28 0x00
                  0x30 0x00
                  0x40 0x01
                  0x06
                  0x08 0x00
                  0x12 0x02 0x01 0x02))
          (seq (.toByteArray output))))))

(deftest protobuf-parser-rejects-truncated-length-delimited-fields
  (testing "syntax errors are codec errors, not semantic validation"
    (is (thrown? Exception
                 (protobuf/decode-pull (byte-array-from 0x1a 0x04 0x72))))))

(deftest protobuf-parser-skips-unknown-fields
  (is (= "r-client"
         (:client-revision
           (protobuf/decode-pull
             (byte-array-from
               0x48 0x7f
               0x51 1 2 3 4 5 6 7 8
               0x5a 0x02 0x01 0x02
               0x65 1 2 3 4
               0x1a 0x08
               (int \r) (int \-) (int \c) (int \l)
               (int \i) (int \e) (int \n) (int \t)))))))

(deftest protobuf-parser-rejects-malformed-wire-fields
  (doseq [[description data]
          [["truncated varint" (byte-array-from 0x48 0x80)]
           ["truncated fixed64" (byte-array-from 0x51 1 2 3 4 5 6 7)]
           ["truncated fixed32" (byte-array-from 0x65 1 2 3)]
           ["truncated length-delimited field" (byte-array-from 0x5a 0x02 0x01)]
           ["unsupported start-group wire type" (byte-array-from 0x4b)]
           ["unsupported end-group wire type" (byte-array-from 0x4c)]
           ["zero field number" (byte-array-from 0x00)]
           ["known field with invalid wire type" (byte-array-from 0x0a 0x00)]
           ["overlong varint" (byte-array-from 0x48
                                0x80 0x80 0x80 0x80 0x80
                                0x80 0x80 0x80 0x80 0x80)]]]
    (testing description
      (is (= ::protobuf/malformed-protobuf
             (:type (test/catch-ex-data #(protobuf/decode-pull data))))))))

(deftest protobuf-parser-rejects-supported-fields-with-wrong-wire-types
  (doseq [[field data]
          [[1 (byte-array-from 0x0a 0x00)]
           [2 (byte-array-from 0x10 0x00)]
           [3 (byte-array-from 0x18 0x00)]
           [4 (byte-array-from 0x22 0x00)]
           [5 (byte-array-from 0x28 0x00)]
           [6 (byte-array-from 0x30 0x00)]
           [7 (byte-array-from 0x38 0x00)]
           [8 (byte-array-from 0x42 0x00)]]]
    (testing (str "field " field)
      (is (= ::protobuf/malformed-protobuf
             (:type (test/catch-ex-data #(protobuf/decode-pull data))))))))

(deftest decodes-the-official-zero-based-roaring-page-selector
  (let [bitmap (doto (RoaringBitmap.)
                 (.add 0)
                 (.add 2))
        output (ByteArrayOutputStream.)]
    (.serialize bitmap (DataOutputStream. output))
    (is (= #{1 3}
           (protobuf/decode-page-selector (.toByteArray output))))))

(deftest empty-page-selectors-mean-no-selection
  (is (nil? (protobuf/decode-page-selector nil)))
  (is (nil? (protobuf/decode-page-selector (byte-array 0)))))

(deftest malformed-page-selectors-are-rejected
  (is (= ::protobuf/malformed-page-selector
         (:type (test/catch-ex-data
                  #(protobuf/decode-page-selector
                     (byte-array-from 1 2 3)))))))

(deftest pull-stream-rejects-a-non-positive-domain-page-id
  (let [body (protobuf/pull-stream
               {:server-revision "r-current"
                :database-page-count 1
                :pages {0 (byte-array-from 1 2)}})
        output (ByteArrayOutputStream.)]
    (is (= ::protobuf/invalid-page-id
           (:type (test/catch-ex-data
                    #(ring/write-body-to-stream body nil output)))))))

(deftest pull-stream-orders-pages-by-domain-page-id
  (let [body (protobuf/pull-stream
               {:server-revision "r-current"
                :database-page-count 2
                :pages {2 (byte-array-from 2)
                        1 (byte-array-from 1)}})
        output (ByteArrayOutputStream.)]
    (ring/write-body-to-stream body nil output)
    (is (= (seq (byte-array-from
                  0x15
                  0x0a 0x09
                  (int \r) (int \-) (int \c) (int \u) (int \r)
                  (int \r) (int \e) (int \n) (int \t)
                  0x10 0x02 0x1a 0x00
                  0x28 0x00 0x30 0x00 0x40 0x01
                  0x05 0x08 0x00 0x12 0x01 0x01
                  0x05 0x08 0x01 0x12 0x01 0x02))
          (seq (.toByteArray output))))))
