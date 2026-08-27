(ns linear.adapter.slatedb.key-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.slatedb.key :as key])
  (:import
   (java.nio.charset StandardCharsets)))

(defn- utf8 [value]
  (String. ^bytes value StandardCharsets/UTF_8))

(deftest current-page-key-has-no-revision-component
  (is (= "page/MQ" (utf8 (key/page 1))))
  (is (= "page/MTA" (utf8 (key/page 10)))))

(deftest metadata-keys-name-their-records
  (is (= "head" (utf8 (key/head))))
  (is (= "revision/r-01K002" (utf8 (key/revision "r-01K002")))))
