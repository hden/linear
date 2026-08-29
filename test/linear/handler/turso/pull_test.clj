(ns linear.handler.turso.pull-test
  (:require
   [clojure.test :refer [deftest is]]
   [linear.handler.turso.pull :as pull]
   [linear.usecase.database :as database]
   [ring.core.protocols :as ring])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.util Arrays)))

(defn- request-bytes [& values]
  (byte-array values))

(deftest pull-handler-resolves-database-and-streams-pull-result
  (let [snapshot (reify
                   java.lang.AutoCloseable
                   (close [_])
                   database/Snapshot
                   (-revision-id [_] "r-current")
                   (-size [_] 1)
                   (-fetch-pages-by-ids [_ _]
                     {1 (byte-array [1 2])}))
        context  {:database-reader
                  (reify database/DatabaseReader
                    (-open-read-session [_ _]
                      (reify
                        java.lang.AutoCloseable
                        (close [_])
                        database/DatabaseReadSession
                        (-head [_] snapshot)
                        (-as-of [_ revision]
                          (is (= "r-target" revision))
                          snapshot)
                        (-changes-since [_ _ _] #{}))))
                  :database-resolver
                  (reify database/DatabaseResolver
                    (-resolve-database [_ database-id]
                      (is (= "d-01M11GV3ER6E777ERMD0DK7CA1" database-id))
                      {:id database-id
                       :display-name "Primary"
                       :keychain ::keychain}))}
        response ((pull/handler context)
                  {:path-params {:id "d-01M11GV3ER6E777ERMD0DK7CA1"}
                   :body (ByteArrayInputStream.
                           (request-bytes 0x12 0x08
                                          (int \r) (int \-) (int \t) (int \a) (int \r)
                                          (int \g) (int \e) (int \t)
                                          0x1a 0x00))})
        output   (ByteArrayOutputStream.)]
    (is (= 200 (:status response)))
    (ring/write-body-to-stream (:body response) response output)
    (is (Arrays/equals
          (byte-array [0x15
                       0x0a 0x09
                       (int \r) (int \-) (int \c) (int \u) (int \r)
                       (int \r) (int \e) (int \n) (int \t)
                       0x10 0x01 0x1a 0x00
                       0x28 0x00 0x30 0x00 0x40 0x01
                       0x06 0x08 0x00 0x12 0x02 0x01 0x02])
          (.toByteArray output)))))

(deftest pull-handler-accepts-a-byte-array-body
  (let [context {:opaque "application-context"}]
    (with-redefs [database/pull-for
                  (fn [actual-context actual-database-id actual-options]
                    (is (= context actual-context))
                    (is (= "d-01M11GV3ER6E777ERMD0DK7CA1"
                           actual-database-id))
                    (is (= {:server-revision nil
                            :client-revision nil
                            :page-ids nil}
                           actual-options))
                    {:server-revision "r-current"
                     :database-page-count 0
                     :pages {}})]
      (let [response ((pull/handler context)
                      {:path-params {:id "d-01M11GV3ER6E777ERMD0DK7CA1"}
                       :body (byte-array [0x1a 0x00])})]
        (is (= 200 (:status response)))))))

(deftest pull-handler-rejects-a-body-with-an-unsupported-type
  (let [response ((pull/handler {}) {:body "not-an-input-stream"})]
    (is (= 400 (:status response)))))
