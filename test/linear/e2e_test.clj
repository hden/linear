(ns linear.e2e-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [linear.e2e :as e2e])
  (:import
   (java.lang ProcessHandle)
   (java.util.concurrent TimeUnit)))

(defn- client-process [script]
  (.start (ProcessBuilder. ["sh" "-c" script])))

(deftest client-wait-accepts-successful-exit
  (let [process (client-process "exit 0")]
    (try
      (is (nil? (#'e2e/wait-client! process 1000)))
      (is (not (.isAlive process)))
      (finally
        (.destroyForcibly process)
        (.waitFor process 5 TimeUnit/SECONDS)))))

(deftest client-wait-reports-nonzero-exit
  (let [process (client-process "exit 7")]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"E2E failed"
            (#'e2e/wait-client! process 1000)))
      (is (= 7 (.exitValue process)))
      (finally
        (.destroyForcibly process)
        (.waitFor process 5 TimeUnit/SECONDS)))))

(deftest client-wait-bounds-timeout-and-releases-process
  (let [process (client-process "exec sleep 30")
        started (System/nanoTime)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timed out after 50 ms"
            (#'e2e/wait-client! process 50)))
      (is (not (.isAlive process)))
      (is (< (/ (- (System/nanoTime) started) 1000000) 5500))
      (finally
        (.destroyForcibly process)
        (.waitFor process 5 TimeUnit/SECONDS)))))

(deftest client-wait-releases-descendants-on-failure
  (doseq [[script timeout-ms message]
          [["sleep 30 & echo $!; wait" 50 #"timed out"]
           ["sleep 30 & echo $!; sleep 0.2; exit 7" 1000 #"E2E failed"]]]
    (let [process (client-process script)]
      (try
        (with-open [reader (io/reader (.getInputStream process))]
          (let [child (.get (ProcessHandle/of (Long/parseLong (.readLine reader))))]
            (try
              (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                    (#'e2e/wait-client! process timeout-ms)))
              (is (not (.isAlive process)))
              (is (not (.isAlive child)))
              (finally
                (.destroyForcibly child)))))
        (finally
          (.destroyForcibly process)
          (.waitFor process 5 TimeUnit/SECONDS))))))
