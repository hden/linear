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

(defn revision-awaitable
  [{::keys [revision-store]}]
  revision-store)

(defn max-long-poll-timeout-ms
  {:malli/schema [:-> :map pos-int?]}
  [{::keys [max-long-poll-timeout-ms]
    :or {max-long-poll-timeout-ms 5000}}]
  max-long-poll-timeout-ms)

(defn checkables [context]
  [(transactable context) (evaluator context)])
