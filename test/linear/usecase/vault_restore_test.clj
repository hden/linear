(ns linear.usecase.vault-restore-test
  (:require
   [boring.core :as cbor]
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [linear.test :refer [catch-ex-data]]
   [linear.test-data.vault :as data]
   [linear.usecase.keychain :as keychain]
   [linear.usecase.vault :as vault])
  (:import
   (java.util Base64)))

(defn- recovery-token []
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (cbor/encode [1 "v-test" "master" (byte-array [1 2 3])])))

(defn- restore [context]
  (vault/restore! context {:actor "owner" :vault-id "v-test" :token (recovery-token)}))

(deftest restoring-a-deleted-vault-stores-the-recovered-key
  (let [{:keys [context state calls]} (data/fixture {:stored {:id "v-test"}})]
    (is (nil? (restore context)))
    (let [[write :as writes] (data/calls-for calls {:operation :write})
          [unwrap-call :as unwrap-calls] (data/calls-for calls {:operation :unwrap})]
      (is (= 1 (count writes)))
      (is (= "v-test" (:vault-id write)))
      (is (= "master" (:encrypted-by write)))
      (is (= [1 2 3] (vec (:ciphertext write))))
      (is (= 1 (count unwrap-calls)))
      (is (= [1 2 3] (vec (:ciphertext unwrap-call))))
      (is (= "v-test" (String. ^bytes (:associated-data unwrap-call) "UTF-8"))))
    (is (= :active (:state (vault/get-state context {:actor "owner" :vault-id "v-test"}))))
    (is (= "v-test" (:id @state)))
    (testing "repeating restoration keeps the existing key"
      (is (nil? (restore context)))
      (is (= 1 (count (data/calls-for calls {:operation :write})))))))

(deftest restoring-an-active-vault-does-not-overwrite-its-key
  (let [stored {:id "v-test" :encrypted-by "existing-master" :ciphertext (byte-array [9])}
        {:keys [context state calls]} (data/fixture {:stored stored})]
    (is (nil? (restore context)))
    (is (= stored @state))
    (is (empty? (data/calls-for calls {:operation :write})))
    (testing "the recovery token is still authenticated"
      (is (= 1 (count (data/calls-for calls {:operation :unwrap})))))))

(deftest invalid-recovery-tokens-do-not-change-the-vault
  (doseq [[state stored] [["deleted" {:id "v-test"}]
                          ["active" {:id "v-test" :encrypted-by "existing-master" :ciphertext (byte-array [9])}]]]
    (testing (str state ": ciphertext cannot be authenticated")
      (let [{:keys [context calls] stored-state :state}
            (data/fixture {:stored stored :unwrap (constantly nil)})
            error-data (catch-ex-data #(restore context))]
        (is (= ::anomaly/incorrect (::anomaly/category error-data)))
        (is (= ::vault/invalid-recovery-token (:reason error-data)))
        (is (= stored @stored-state))
        (is (empty? (data/calls-for calls {:operation :write})))))))

(deftest key-service-failures-leave-the-vault-deleted
  (let [failure (ex-info "Key service failed" {::anomaly/category ::anomaly/fault
                                               :reason ::keychain/key-service-failed})
        {:keys [context state calls]} (data/fixture {:stored {:id "v-test"} :unwrap #(throw failure)})
        error (try (restore context) (catch clojure.lang.ExceptionInfo error error))]
    (is (= (ex-data failure) (ex-data error)))
    (is (= "Key service failed" (.getMessage ^Exception error)))
    (is (= {:id "v-test"} @state))
    (is (empty? (data/calls-for calls {:operation :write})))))

(deftest unexpected-unwrapping-failures-are-not-invalid-tokens
  (let [{:keys [context state calls]}
        (data/fixture {:stored {:id "v-test"}
                       :unwrap #(throw (IllegalStateException. "Unexpected decrypt failure"))})]
    (is (thrown-with-msg? IllegalStateException #"Unexpected decrypt failure" (restore context)))
    (is (= {:id "v-test"} @state))
    (is (empty? (data/calls-for calls {:operation :write})))))

(deftest restoration-requires-manage-permission-before-unwrapping
  (let [{:keys [context state calls]} (data/fixture {:stored {:id "v-test"} :permission :pull})
        error-data (catch-ex-data #(restore context))]
    (is (= ::anomaly/forbidden (::anomaly/category error-data)))
    (is (= {:id "v-test"} @state))
    (is (empty? (data/calls-for calls {:operation :unwrap})))
    (is (empty? (data/calls-for calls {:operation :write})))))
