(ns linear.adapter.crypto
  (:require
   [integrant.core :as ig]
   [linear.adapter.crypto.gcp-kms :as gcp-kms]
   [linear.adapter.crypto.tempel :as tempel])
  (:import
   (java.io Closeable)))

(defmethod ig/init-key ::key-service [_ {:keys [provider] :as options}]
  (case provider
    :tempel (tempel/open options)
    :gcp-kms (gcp-kms/open options)))

(defmethod ig/halt-key! ::key-service [_ service]
  (.close ^Closeable service))
