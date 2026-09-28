(ns linear.server.jetty
  (:require
   [integrant.core :as ig])
  (:import
   (org.eclipse.jetty.http UriCompliance UriCompliance$Violation)
   (org.eclipse.jetty.server Connector HttpConnectionFactory Server)))

(defn- allow-encoded-path-separators! [^Server server]
  (let [uri-compliance
        (.with UriCompliance/DEFAULT
               "LINEAR_ENCODED_PATH_SEPARATOR"
               (into-array UriCompliance$Violation
                           [UriCompliance$Violation/AMBIGUOUS_PATH_SEPARATOR]))]
    (doseq [^Connector connector (.getConnectors server)
            factory (.getConnectionFactories connector)
            :when (instance? HttpConnectionFactory factory)]
      (.setUriCompliance (.getHttpConfiguration ^HttpConnectionFactory factory)
                         uri-compliance))))

(defmethod ig/init-key ::allow-encoded-path-separators [_ _]
  allow-encoded-path-separators!)
