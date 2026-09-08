(ns linear.adapter.slatedb.key
  (:require
   [linear.usecase.database.revisions :as revisions])
  (:import
   (java.nio.charset StandardCharsets)
   (java.util Base64)))

(defn- utf8 [value]
  (.getBytes ^String value StandardCharsets/UTF_8))

(defn- page-component [page-id]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (utf8 (str page-id))))

(defn head
  {:malli/schema [:-> [:fn bytes?]]}
  []
  (utf8 "head"))

(defn revision
  {:malli/schema [:-> ::revisions/revision-id [:fn bytes?]]}
  [revision-id]
  (utf8 (str "revision/" revision-id)))

(defn page
  {:malli/schema [:-> ::revisions/page-id [:fn bytes?]]}
  [page-id]
  (utf8 (str "page/" (page-component page-id))))
