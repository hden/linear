(ns linear.adapter.crypto
  (:require
   [integrant.core :as ig]
   [linear.adapter.crypto.gcp-kms :as gcp-kms]
   [linear.adapter.crypto.tempel :as tempel])
  (:import
   (java.io Closeable)))

(defmethod ig/init-key ::key-service [_ {:keys [deployment-target gcp-kms-key-name]}]
  (case deployment-target
    "dev" (tempel/open {:key-id "dev-ephemeral"})
    "gcp" (gcp-kms/open {:key-id gcp-kms-key-name})))

(defmethod ig/halt-key! ::key-service [_ service]
  (.close ^Closeable service))
