(ns linear.adapter.database.schema)

(def statement-arg-map
  [:map
   [:statement map?]
   [:parse-fn {:optional true} ifn?]])

(def connection-query-arg-map
  statement-arg-map)

(def connection-transact-arg-map
  [:map
   [:statements [:or
                 [:vector statement-arg-map]
                 [:sequential statement-arg-map]
                 [:set statement-arg-map]]]
   [:timeout-ms {:optional true} pos-int?]])
