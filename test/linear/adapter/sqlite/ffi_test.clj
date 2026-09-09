(ns linear.adapter.sqlite.ffi-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.sqlite.ffi :as ffi]))

(deftest ^:integration sqlite-library-loads-by-system-name
  (ffi/load!)
  (is (re-matches #"\d+\.\d+\.\d+" (ffi/version))))
