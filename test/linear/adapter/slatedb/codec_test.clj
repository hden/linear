(ns linear.adapter.slatedb.codec-test
  (:require
   [boring.core :as cbor]
   [clojure.test :refer [deftest is]]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.key :as key]
   [linear.test :as test]
   [linear.usecase.keychain :as keychain])
  (:import
   (java.util Arrays)))

(defn- normalize-options [options]
  (update options :associated-data seq))

(defn- fake-keychain [secret]
  (reify keychain/Keychain

    (-encrypt [_ plaintext options]
      (cbor/encode {:secret secret
                    :options (normalize-options options)
                    :plaintext plaintext}))
    (-decrypt [_ ciphertext options]
      (let [{ciphertext-secret :secret
             ciphertext-options :options
             :keys [plaintext]} (cbor/decode ciphertext)]
        (when-not (= secret ciphertext-secret)
          (throw (ex-info "Wrong keychain" {})))
        (when-not (= (normalize-options options) ciphertext-options)
          (throw (ex-info "Wrong options" {})))
        plaintext))))

(deftest page-codec-requires-the-keychain-and-record-key
  (let [keychain   (fake-keychain :primary)
        other      (fake-keychain :other)
        source     (key/page 1)
        target     (key/page 2)
        page       (byte-array [1 2 3])
        ciphertext (codec/encode-page keychain {:record-key source :page page})]
    (is (Arrays/equals page (codec/decode-page keychain {:record-key source :value ciphertext})))
    (is (= ::codec/unreadable-record
           (:reason (test/catch-ex-data
                      #(codec/decode-page other {:record-key source :value ciphertext})))))
    (is (= ::codec/unreadable-record
           (:reason (test/catch-ex-data
                      #(codec/decode-page keychain {:record-key target :value ciphertext})))))))

(deftest revision-codec-retains-before-images-and-absent-pages
  (let [keychain  (fake-keychain :primary)
        record-key (key/revision "r-01K002")
        revision  {:parent {:revision-id "r-01K001" :database-page-count 1 :history-expires-at 123}
                   :undo-pages {1 (byte-array [1]) 2 nil}}
        decoded   (codec/decode-revision keychain {:record-key record-key
                                                   :value (codec/encode-revision keychain {:record-key record-key :revision revision})})]
    (is (= (:parent revision) (:parent decoded)))
    (is (= #{1 2} (set (keys (:undo-pages decoded)))))
    (is (nil? (get-in decoded [:undo-pages 2])))
    (is (Arrays/equals (get-in revision [:undo-pages 1])
                       (get-in decoded [:undo-pages 1])))))
