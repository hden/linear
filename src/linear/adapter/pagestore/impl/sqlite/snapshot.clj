(ns linear.adapter.pagestore.impl.sqlite.snapshot
  "Logical-page access for a SQLite VFS callback."
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.adapter.pagestore.impl.core :as pagestore]))

(defn- invalid-page-number! [page-number]
  (throw (ex-info "SQLite page number must be positive"
                  {::anomaly/category ::anomaly/incorrect
                   :reason            ::invalid-page-number
                   :page-number       page-number})))

(defn page-id [page-number]
  (when-not (and (integer? page-number) (pos? page-number))
    (invalid-page-number! page-number))
  (str page-number))

(defn page-count [database]
  (pagestore/size database))

(defn fetch-pages [database page-numbers]
  (let [page-numbers (set page-numbers)
        pages        (pagestore/fetch-pages-by-ids
                       database
                       {:ids (into #{} (map page-id) page-numbers)})]
    (into {}
          (map (fn [page-number]
                 [page-number (get pages (page-id page-number))]))
          page-numbers)))
