(ns linear.usecase.core)

(defn keychain
  [{keychain-factory ::keychain}]
  (keychain-factory))

(defn master-key
  [{::keys [master-key]}]
  master-key)

(defn transactable
  [{::keys [database]}]
  database)

(defn evaluator
  [{::keys [evaluator]}]
  evaluator)

(defn consistent-readable
  [{::keys [revision-store]}]
  revision-store)

(defn revision-writable
  [{::keys [revision-store]}]
  revision-store)

(defn checkables [context]
  [(transactable context) (evaluator context)])
