(ns linear.usecase.core)

(defn key-generator
  [{::keys [key-service]}]
  key-service)

(defn key-protection
  [{::keys [key-service]}]
  key-service)

(defn transactable
  [{::keys [database]}]
  database)

(defn evaluator
  [{::keys [evaluator]}]
  evaluator)

(defn sync-progress-reader
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
