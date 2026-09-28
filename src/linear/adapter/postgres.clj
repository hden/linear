(ns linear.adapter.postgres
  (:require
   [integrant.core :as ig]
   [linear.adapter.postgres.database]
   [linear.adapter.postgres.datasource]
   [linear.adapter.postgres.grant]
   [linear.adapter.postgres.vault]))

(derive :duct.database.sql/hikaricp ::datasource)

(defmethod ig/expand-key ::module [_ _]
  {})
