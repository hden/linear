(ns linear.adapter.sqlite.protocol-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.adapter.sqlite.protocol :as sqlite]))

(defn- fake-file [calls result]
  (reify sqlite/File
    (-close [_] nil)
    (-read [_ offset length]
      (swap! calls conj [:read offset length])
      result)
    (-write [_ _ _] nil)
    (-truncate [_ _] nil)
    (-sync [_] nil)
    (-size [_] 0)))

(deftest file-wrappers-delegate-to-opaque-file
  (let [calls  (atom [])
        result {:bytes (byte-array 4) :short-read? false}
        file   (fake-file calls result)]
    (is (identical? result (sqlite/read file 0 4)))
    (is (= [[:read 0 4]] @calls))))

(deftest filesystem-open-returns-an-opaque-file
  (let [file       (fake-file (atom []) {:bytes (byte-array 0) :short-read? false})
        filesystem (reify sqlite/FileSystem
                     (-open [_ _] {:file file :mode :read-only})
                     (-delete [_ _] nil)
                     (-access [_ _] true)
                     (-full-path [_ {:keys [path]}] path))
        result (sqlite/open filesystem
                            {:path "/db"
                             :requested-mode :read-write
                             :kind :main-db
                             :options #{}})]
    (is (identical? file (:file result)))
    (is (= :read-only (:mode result)))))
