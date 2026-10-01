(ns linear.e2e
  (:require
   [clojure.java.io :as io]
   [integrant.core :as integrant]
   [linear.test :as test]
   [linear.test-data.jwt :as jwt])
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

(defn- server-url [system]
  (let [server    (:server (:duct.server.http/jetty system))
        connector (aget (.getConnectors server) 0)
        port      (.getLocalPort ^NetworkConnector connector)]
    (str "http://127.0.0.1:" port)))

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

(defn- run-client! [url tokens]
  (let [builder     (ProcessBuilder. ["npm" "run" "e2e" "--" url])
        environment (.environment builder)]
    (doseq [[name token] tokens]
      (.put environment name token))
    (let [process (-> builder
                      (.inheritIO)
                      (.start))]
      (wait-client! process 120000))))

(defn -main []
  (with-open [fixture (jwt/fixture)]
    (let [manager (str "auth0|e2e-manager-" (random-uuid))
          reader (str "auth0|e2e-reader-" (random-uuid))
          ungranted (str "auth0|e2e-ungranted-" (random-uuid))
          system (test/run {:config   (config)
                            :keys     #{:duct.server.http/jetty}
                            :profiles [:test :main]
                            :vars     (assoc (jwt/oidc-vars fixture) 'port 0)})]
      (try
        (run-client! (server-url system)
                     {"LINEAR_E2E_MANAGER_TOKEN" (jwt/access-token fixture {:subject manager})
                      "LINEAR_E2E_READER_TOKEN" (jwt/access-token fixture {:subject reader})
                      "LINEAR_E2E_READER_SUBJECT" reader
                      "LINEAR_E2E_UNGRANTED_TOKEN" (jwt/access-token fixture {:subject ungranted})})
        (finally
          (integrant/halt! system))))))
