(ns linear.adapter.sqlite.ffi-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.sqlite.ffi :as ffi]
   [linear.adapter.sqlite.test-support :as support]))

(deftest sqlite-library-loads-by-system-name
  (when (support/sqlite-available?)
    (ffi/load!)
    (is (re-matches #"\d+\.\d+\.\d+" (ffi/version)))))
