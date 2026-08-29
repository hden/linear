(ns linear.handler.turso.common
  (:require
   [integrant.core :as integrant]
   [linear.usecase.database :as database]
   [taoensso.tempel :as tempel]))

;; TODO: replace the fixed database and generated keychain with application configuration.
(def database-id "d-01M11GV3ER6E777ERMD0DK7CA1")
(defonce ^:private keychain (delay (tempel/keychain)))

(defn database []
  {:id database-id
   :display-name "Primary"
   :keychain @keychain})

(defrecord FixedDatabaseResolver [database]
  database/DatabaseResolver
  (-resolve-database [_ requested-id]
    (when (= requested-id (:id database))
      database)))

(defmethod integrant/init-key ::database-resolver
  [_ _]
  (->FixedDatabaseResolver (database)))
