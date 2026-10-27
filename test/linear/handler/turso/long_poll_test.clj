(ns linear.handler.turso.long-poll-test
  (:require
   [clojure.test :refer [deftest is]]
   [duct.test :refer [with-system]]
   [linear.adapter.postgres]
   [linear.adapter.slatedb.store]
   [linear.handler.turso.pull :as pull]
   [linear.test :refer [run]]
   [linear.test-data.postgres :as postgres-data]
   [linear.test-data.slatedb :as slatedb-data]
   [linear.usecase.core :as core]
   [linear.usecase.database.revisions :as revisions]
   [ring.adapter.jetty :as jetty])
  (:import
   (java.net Socket)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(deftest ^:integration disconnected-http-pulls-release-their-revision-waits
  (with-system [system (run {:keys [:duct.database/sql :duct.migrator/ragtime
                                    :linear.adapter.slatedb.store/store]})]
    (let [datasource (:duct.database.sql/hikaricp system)
          store (:linear.adapter.slatedb.store/store system)
          {:keys [database-id key-protection keychain]}
          (postgres-data/create-database! {:datasource datasource})
          events (LinkedBlockingQueue.)
          reader (reify
                   revisions/ConsistentReadable
                   (-read-consistently [_ f database]
                     (revisions/-read-consistently store f database))
                   revisions/RevisionAwaitable
                   (-await-revision [_ args]
                     (.offer events :entered)
                     (try
                       (revisions/await-revision store args)
                       (finally (.offer events :released)))))
          handler (pull/handler {::core/database datasource ::core/key-service key-protection
                                 ::core/revision-store reader ::core/max-long-poll-timeout-ms 100})
          server (jetty/run-jetty #(handler (assoc % :identity {:sub "actor-1"}
                                                   :path-params {:id database-id}))
                                  {:port 0 :join? false})
          port (.getLocalPort (first (.getConnectors server)))
          body (byte-array (map unchecked-byte [0x1a 6 0x72 0x2d 0x72 0x6f 0x6f 0x74 0x20 0x88 0x27]))]
      (try
        (slatedb-data/store-root! {:store store :database-id database-id :keychain keychain :pages {}})
        (dotimes [_ 10]
          (with-open [socket (Socket. "127.0.0.1" port)]
            (let [output (.getOutputStream socket)]
              (.write output (.getBytes (str "POST /pull-updates HTTP/1.1\r\nHost: localhost\r\n"
                                          "Connection: close\r\nContent-Length: " (alength body) "\r\n\r\n")
                               StandardCharsets/UTF_8))
              (.write output body)
              (.flush output)
              (is (= :entered (.poll events 2 TimeUnit/SECONDS)))
              (.setSoLinger socket true 0)))
          ;; This handler does not observe disconnects during a pull. The
          ;; bounded revision wait must finish without a response write.
          (is (= :released (.poll events 2 TimeUnit/SECONDS))))
        (is (nil? (.poll events)))
        (finally
          (.stop server)
          (.join server))))))
