(ns linear.adapter.slatedb.codec-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.key :as key]
   [linear.test :as test]
   [taoensso.tempel :as tempel])
  (:import
   (java.util Arrays)))

(deftest page-codec-requires-the-keychain-and-record-key
  (let [keychain   (tempel/keychain)
        other      (tempel/keychain)
        source     (key/page 1)
        target     (key/page 2)
        page       (byte-array [1 2 3])
        ciphertext (codec/encode-page keychain source page)]
    (is (Arrays/equals page (codec/decode-page keychain source ciphertext)))
    (is (= ::codec/unreadable-record
           (:reason (test/catch-ex-data
                      #(codec/decode-page other source ciphertext)))))
    (is (= ::codec/unreadable-record
           (:reason (test/catch-ex-data
                      #(codec/decode-page keychain target ciphertext)))))))

(deftest revision-codec-retains-changed-pages
  (let [keychain  (tempel/keychain)
        record-key (key/revision "r-01K002")
        revision  {:revision-id "r-01K002"
                   :parent "r-01K001"
                   :database-page-count 1
                   :pages {1 (byte-array [1])}}
        decoded   (codec/decode-revision
                    keychain record-key
                    (codec/encode-revision keychain record-key revision))]
    (is (= (dissoc revision :pages) (dissoc decoded :pages)))
    (is (= #{1} (set (keys (:pages decoded)))))
    (is (Arrays/equals (get-in revision [:pages 1])
                       (get-in decoded [:pages 1])))))
