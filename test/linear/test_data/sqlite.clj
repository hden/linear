(ns linear.test-data.sqlite
  (:require
   [linear.usecase.database.revisions :as revisions])
  (:import
   (java.nio.file Files)
   (java.sql DriverManager)
   (java.util Arrays)))

(defrecord Snapshot [revision-id pages]
  revisions/Snapshot
  (-revision-id [_]
    revision-id)
  (-size [_]
    (count pages))
  (-fetch-pages-by-ids [_ {:keys [ids]}]
    (select-keys pages ids)))

(defn- page-size [^bytes image]
  (let [value (bit-or (bit-shift-left (bit-and (aget image 16) 0xff) 8)
                      (bit-and (aget image 17) 0xff))]
    (if (= value 1) 65536 value)))

(defn snapshot [{:keys [image revision-id] :or {revision-id "r-0"}}]
  (let [size       (page-size image)
        page-count (quot (alength ^bytes image) size)]
    (->Snapshot revision-id
                (into {}
                      (map (fn [page-number]
                             [page-number
                              (Arrays/copyOfRange image
                                                  (* (dec page-number) size)
                                                  (* page-number size))]))
                      (range 1 (inc page-count))))))

(defn pages [options]
  (:pages (snapshot options)))

(defn- create-sqlite-image []
  (let [path (Files/createTempFile "linear-sqlite-evaluator" ".db"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [connection (DriverManager/getConnection (str "jdbc:sqlite:" path))
                  statement  (.createStatement connection)]
        (.execute statement "PRAGMA journal_mode=WAL")
        (.execute statement "CREATE TABLE t (id INTEGER PRIMARY KEY, value TEXT)")
        (.execute statement "INSERT INTO t (value) VALUES ('before-evaluator')"))
      (Files/readAllBytes path)
      (finally
        (Files/deleteIfExists path)))))

(def ^:private sqlite-image-cache
  (delay (create-sqlite-image)))

(defn sqlite-image []
  (aclone ^bytes (force sqlite-image-cache)))
