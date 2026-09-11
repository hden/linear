(ns linear.test-data.slatedb
  (:require
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]))

(defn root-records [{:keys [keychain pages revision-id parent]
                     :or   {revision-id "r-root"
                            parent nil}}]
  (let [revision     {:revision-id         revision-id
                      :parent              parent
                      :database-page-count (count pages)
                      :pages               pages}
        revision-key (key/revision (:revision-id revision))]
    (into [[revision-key
            (codec/encode-revision keychain {:record-key revision-key :revision revision})]]
          (concat
            (map (fn [[page-id page]]
                   (let [page-key (key/page page-id)]
                     [page-key (codec/encode-page keychain {:record-key page-key :page page})]))
                 pages)
            [[(key/head)
              (codec/encode-head {:revision-id (:revision-id revision)})]]))))

(defn store-root! [{:keys [store database-id] :as options}]
  (with-open [database    (connection/database store {:database-id database-id})
              transaction (connection/writable-transaction database)]
    (ffi/await (ffi/write-values transaction (root-records options)))
    (ffi/await (ffi/commit-transaction transaction))))
