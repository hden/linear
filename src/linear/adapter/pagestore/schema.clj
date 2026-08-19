(ns linear.adapter.pagestore.schema)

(def id
  [:fn (fn [value]
         (and (string? value)
              (not (empty? value))))])

(def page-bytes
  [:fn #(instance? (Class/forName "[B") %)])

(def revision
  [:map
   [:id id]
   [:parent [:maybe id]]
   [:database-page-count :int]
   [:pages [:map-of id page-bytes]]])

(def database-create-arg-map
  [:map
   [:db :string]
   [:from {:optional true} :string]])

(def database-fetch-pages-by-ids-arg-map
  [:map
   [:ids [:set :string]]])
