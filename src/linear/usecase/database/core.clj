(ns linear.usecase.database.core)

(defn consistent-view
  [database]
  (::consistent-view database))

(defn evaluator
  [database]
  (::evaluator database))

(defn database
  [database {:keys [consistent-view evaluator]}]
  (assoc database
         ::consistent-view consistent-view
         ::evaluator evaluator))
