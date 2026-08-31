(ns linear.e2e
  (:require
   [clojure.java.io :as io]
   [integrant.core :as integrant]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.adapter.sqlite.test-support :as sqlite]
   [linear.handler.turso.common :as common]
   [linear.test :as test])
  (:import
   (org.eclipse.jetty.server NetworkConnector)))

(defn- read-config [file]
  (integrant/read-string {:readers {'duct/include #(read-config (io/file %))
                                    'duct/resource io/resource}}
                         (slurp file)))

(defn- config []
  (-> (read-config (io/file "duct.edn"))
      (assoc-in [:vars 'port] {:type :int :default 3000})
      (update-in [:system :duct.module/web :handler-opts]
                 dissoc
                 :linear.usecase.core/postgres-datasource)))

(defn- root-records [keychain pages]
  (let [revision     {:revision-id         "r-root"
                      :parent              nil
                      :database-page-count (count pages)
                      :pages               pages}
        revision-key (key/revision (:revision-id revision))]
    (into [[revision-key
            (codec/encode-revision keychain revision-key revision)]]
          (concat
            (map (fn [[page-id page]]
                   (let [page-key (key/page page-id)]
                     [page-key (codec/encode-page keychain page-key page)]))
                 pages)
            [[(key/head)
              (codec/encode-head {:revision-id (:revision-id revision)})]]))))

(defn- seed! [store database pages]
  (with-open [leased-database (connection/database store (:id database))
              transaction     (connection/writable-transaction leased-database)]
    (ffi/await (ffi/write-values transaction (root-records (:keychain database) pages)))
    (ffi/await (ffi/commit-transaction transaction))))

(defn- server-url [system]
  (let [server    (:server (:duct.server.http/jetty system))
        connector (aget (.getConnectors server) 0)
        port      (.getLocalPort ^NetworkConnector connector)]
    (str "http://127.0.0.1:" port "/d/"
         (:id (common/database)))))

(defn- run-client! [url]
  (let [process (-> (ProcessBuilder. ["npm" "run" "e2e" "--" url])
                    (.inheritIO)
                    (.start))
        exit    (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "JavaScript Turso sync E2E failed" {:exit exit})))))

(defn -main []
  (let [system (test/run {:config   (config)
                          :keys     #{:duct.server.http/jetty}
                          :profiles [:test :main]
                          :vars     {'port 0}})
        database (common/database)]
    (try
      (seed! (:linear.adapter.slatedb.store/store system)
             database
             (:pages (sqlite/snapshot (sqlite/sqlite-image))))
      (run-client! (server-url system))
      (finally
        (integrant/halt! system)))))
