(ns linear.e2e
  (:require
   [clojure.java.io :as io]
   [integrant.core :as integrant]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.adapter.slatedb.codec :as codec]
   [linear.adapter.slatedb.connection :as connection]
   [linear.adapter.slatedb.ffi :as ffi]
   [linear.adapter.slatedb.key :as key]
   [linear.test :as test]
   [linear.test-data.sqlite :as sqlite-data]
   [linear.usecase.keychain :as keychain]
   [next.jdbc :as jdbc]
   [taoensso.tempel :as tempel])
  (:import
   (java.lang ProcessHandle)
   (java.util.concurrent TimeUnit TimeoutException)
   (org.eclipse.jetty.server NetworkConnector)))

(defn- read-config [file]
  (integrant/read-string {:readers {'duct/include #(read-config (io/file %))
                                    'duct/resource io/resource}}
                         (slurp file)))

(defn- config []
  (-> (read-config (io/file "duct.edn"))
      (assoc-in [:vars 'port] {:type :int :default 3000})))

(defn- seed-postgres! [datasource master-key {database-id :id :keys [vault]}]
  (let [vault-id (:id vault)
        transaction-id (str "tx-" (random-uuid))
        attributes-id (str "a-" (random-uuid))
        ciphertext (keychain/encrypt master-key {:associated-data (.getBytes ^String vault-id "UTF-8")
                                                 :value (:keychain vault)})]
    (jdbc/with-transaction [tx datasource]
      (jdbc/execute! tx ["INSERT INTO transactions (id) VALUES (?)" transaction-id])
      (jdbc/execute! tx
                     ["INSERT INTO vaults (id, ciphertext, encrypted_by, created_by) VALUES (?, ?, ?, ?)"
                      vault-id ciphertext "dev-ephemeral" transaction-id])
      (jdbc/execute! tx
                     ["INSERT INTO databases (id, encrypted_by, current_attributes) VALUES (?, ?, ?)"
                      database-id vault-id attributes-id])
      (jdbc/execute! tx
                     ["INSERT INTO database_attributes (id, database_id, display_name, created_by) VALUES (?, ?, ?, ?)"
                      attributes-id database-id "E2E" transaction-id]))))

(defn- root-records [keychain pages]
  (let [revision     {:revision-id         "r-root"
                      :parent              nil
                      :database-page-count (count pages)
                      :pages               pages}
        revision-key (key/revision (:revision-id revision))]
    (into [[revision-key
            (codec/encode-revision keychain {:record-key revision-key :revision revision})]]
          (concat
            (map (fn [[page-id page]]
                   (let [page-key (key/page page-id)]
                     [page-key (codec/encode-page keychain {:record-key page-key :page page})]))
                 pages)
            [[(key/head)
              (codec/encode-head {:revision-id (:revision-id revision)})]]))))

(defn- seed! [store database pages]
  (with-open [leased-database (connection/database store {:database-id (:id database)})
              transaction     (connection/writable-transaction leased-database)]
    (ffi/await (ffi/write-values transaction (root-records (get-in database [:vault :keychain]) pages)))
    (ffi/await (ffi/commit-transaction transaction))))

(defn- server-url [system database-id]
  (let [server    (:server (:duct.server.http/jetty system))
        connector (aget (.getConnectors server) 0)
        port      (.getLocalPort ^NetworkConnector connector)]
    (str "http://127.0.0.1:" port "/d/"
         database-id)))

(defn- stop-client! [processes]
  (let [deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 5))]
    (doseq [^ProcessHandle process processes]
      (.destroyForcibly process))
    (doseq [^ProcessHandle process processes
            :when (.isAlive process)]
      (try
        (.get (.onExit process)
              (max 0 (- deadline (System/nanoTime)))
              TimeUnit/NANOSECONDS)
        (catch TimeoutException error
          (throw (ex-info "JavaScript Turso sync E2E cleanup timed out after 5000 ms"
                          {:timeout-ms 5000 :pid (.pid process)}
                          error)))))))

(defn- wait-client! [^Process process timeout-ms]
  (let [deadline (+ (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS timeout-ms))
        descendants (volatile! #{})
        succeeded? (volatile! false)]
    (try
      (loop []
        (with-open [stream (.descendants process)]
          (vswap! descendants into (iterator-seq (.iterator stream))))
        (let [remaining (- deadline (System/nanoTime))]
          (when-not (and (pos? remaining)
                         (.waitFor process
                                   (min remaining (.toNanos TimeUnit/MILLISECONDS 50))
                                   TimeUnit/NANOSECONDS))
            (if (pos? (- deadline (System/nanoTime)))
              (recur)
              (throw (ex-info (str "JavaScript Turso sync E2E timed out after " timeout-ms " ms")
                              {:timeout-ms timeout-ms}))))))
      (let [exit (.exitValue process)]
        (when-not (zero? exit)
          (throw (ex-info "JavaScript Turso sync E2E failed" {:exit exit}))))
      (vreset! succeeded? true)
      nil
      (finally
        (when-not @succeeded?
          (with-open [stream (.descendants process)]
            (vswap! descendants into (iterator-seq (.iterator stream))))
          (stop-client! (concat @descendants [(.toHandle process)])))))))

(defn- run-client! [url]
  (let [process (-> (ProcessBuilder. ["npm" "run" "e2e" "--" url])
                    (.inheritIO)
                    (.start))]
    (wait-client! process 120000)))

(defn -main []
  (let [system (test/run {:config   (config)
                          :keys     #{:duct.server.http/jetty}
                          :profiles [:test :main]
                          :vars     {'port 0}})]
    (try
      (let [master-key (:linear.adapter.crypto.tempel/master-key system)
            keychain   (crypto/keychain (tempel/keychain))
            database   {:id (str "d-e2e-" (random-uuid))
                        :display-name "E2E"
                        :vault {:id (str "v-e2e-" (random-uuid))
                                :owner nil
                                :created java.time.Instant/EPOCH
                                :keychain keychain}}]
        (seed-postgres! (:duct.database.sql/hikaricp system) master-key database)
        (seed! (:linear.adapter.slatedb.store/store system)
               database
               (sqlite-data/pages {:image (sqlite-data/sqlite-image)}))
        (run-client! (server-url system (:id database))))
      (finally
        (integrant/halt! system)))))
