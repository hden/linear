(ns linear.test-data.slatedb
  (:require
   [linear.adapter.slatedb.store]
   [linear.usecase.database.revisions :as revisions]))

(defn store-root! [{:keys [store database-id keychain pages revision-id]
                    :or {revision-id "r-root"}}]
  (revisions/initialize! store
    {:database {:id database-id :vault {:keychain keychain}}
     :revision {:revision-id revision-id
                :parent nil
                :database-page-count (count pages)
                :pages pages}}))
