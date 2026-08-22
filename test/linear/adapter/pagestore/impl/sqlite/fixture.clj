(ns linear.adapter.pagestore.impl.sqlite.fixture
  (:require
   [linear.adapter.pagestore.impl.core :as pagestore]))

(defrecord FakeDatabase [revision-id page-count pages]
  pagestore/Database
  (-revision-id [_]
    revision-id)
  (-size [_]
    page-count)
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn sqlite-library []
  (or (System/getenv "SQLITE_LIBRARY")
      (throw (ex-info "SQLITE_LIBRARY is required for SQLite tests" {}))))

(defn page-size [^bytes image]
  (let [value (bit-or (bit-shift-left (bit-and (aget image 16) 0xff) 8)
                      (bit-and (aget image 17) 0xff))]
    (if (= value 1) 65536 value)))

(defn page-count [^bytes image]
  (quot (alength image) (page-size image)))

(defn pages [^bytes image]
  (let [size (page-size image)]
    (into {}
          (map (fn [page-number]
                 [(str page-number)
                  (java.util.Arrays/copyOfRange image
                    (* (dec page-number) size)
                    (* page-number size))]))
          (range 1 (inc (page-count image))))))

(defn database [revision-id image]
  (->FakeDatabase revision-id (page-count image) (pages image)))

(defn revision-pages [revision-id image]
  (into {}
        (map (fn [[page-id page]]
               [page-id {revision-id page}]))
        (pages image)))

(defn sqlite-image []
  (let [path (java.nio.file.Files/createTempFile "linear-vfs-source"
                                                  ".db"
                                                  (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [connection (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" path))
                  statement  (.createStatement connection)]
        (.execute statement "PRAGMA journal_mode=WAL")
        (.execute statement "CREATE TABLE t (id INTEGER PRIMARY KEY, value TEXT)")
        (.execute statement "INSERT INTO t (value) VALUES ('through-vfs')"))
      (java.nio.file.Files/readAllBytes path)
      (finally
        (java.nio.file.Files/deleteIfExists path)))))
