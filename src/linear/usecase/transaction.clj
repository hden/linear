(ns linear.usecase.transaction)

(defprotocol Transactable
  (-transact [datasource f options]))

(defmacro with-transaction
  [[binding datasource & [options]] & body]
  `(-transact ~datasource
              (fn [~binding]
                ~@body)
              ~(or options {})))
