(ns linear.adapter.sqlite.ffi-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.sqlite.ffi :as ffi]))

(deftest sqlite-library-reports-a-version
  (when-let [library (System/getenv "SQLITE_LIBRARY")]
    (ffi/load! library)
    (is (re-matches #"\d+\.\d+\.\d+" (ffi/version)))))
