(ns linear.handler.vault-test
  (:require
   [boring.core :as cbor]
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anomaly]
   [linear.adapter.crypto.core :as crypto-core]
   [linear.adapter.crypto.tempel :as crypto]
   [linear.handler.vault :as handler]
   [linear.test-data.tempel :as tempel-data]
   [linear.test-data.vault :as data]
   [linear.usecase.core :as core]
   [linear.usecase.keychain :as keychain]
   [taoensso.tempel :as tempel])
  (:import
   (java.util Base64)))

(def ^:private request
  {:identity {:sub "owner"} :path-params {:id "v-test"}})

(deftest creating-a-vault-returns-its-id-and-grants-the-request-actor-manage-permission
  (let [{:keys [context calls]} (data/fixture {})
        response ((handler/create context) (assoc request :headers {"idempotency-key" "request-key"}))
        [creation] (data/calls-for calls {:operation :create})
        [vault] (:data creation)]
    (is (= {:status 201 :body {:id (:id vault)}} response))
    (is (string? (:id vault)))
    (is (= "owner" (:actor creation)))
    (is (= "request-key" (:idempotency-key creation)))
    (is (= 1 (count (:data creation))))
    (is (= "master" (:encrypted-by vault)))
    (is (= [{:data [{:vault-id (:id vault) :subject "owner" :permission :manage}]}]
           (data/calls-for calls {:operation :grants})))))

(deftest creating-a-vault-requires-a-nonempty-idempotency-header
  (doseq [header [nil "" 42]]
    (let [{:keys [context calls]} (data/fixture {})]
      (is (= {:status 400 :body {:error "Idempotency-Key header is required"}}
             ((handler/create context) (assoc request :headers {"idempotency-key" header}))))
      (is (empty? @calls)))))

(deftest vault-state-is-returned-as-a-wire-format-string
  (doseq [[state stored] [["active" {:id "v-test" :encrypted-by "master" :ciphertext (byte-array [1])}]
                          ["deleted" {:id "v-test"}]]]
    (testing state
      (let [{:keys [context calls]} (data/fixture {:stored stored})]
        (is (= {:status 200 :body {:id "v-test" :state state}} ((handler/get-state context) request)))
        (is (= [{:actor "owner" :vault-id "v-test" :permission :pull}]
               (data/calls-for calls {:operation :permission})))
        (is (= [{:actor "owner" :vault-id "v-test" :lock? false}]
               (data/calls-for calls {:operation :read})))))))

(deftest an-exported-recovery-token-restores-a-deleted-vault
  (let [{:keys [context state]} (data/fixture {})
        recovery ((handler/recovery-token context) request)]
    (is (= 200 (:status recovery)))
    (is (= {"cache-control" "no-store"} (:headers recovery)))
    (is (string? (get-in recovery [:body :token])))
    (is (= {:status 204} ((handler/delete context) request)))
    (is (nil? (:ciphertext @state)))
    (is (= {:status 204} ((handler/restore context) (assoc request :body-params (:body recovery)))))
    (is (= [1 2 3] (vec (:ciphertext @state))))
    (is (= "master" (:encrypted-by @state)))))

(deftest malformed-recovery-requests-are-rejected-before-accessing-capabilities
  (doseq [body [nil {} {:token 1} {:token "token" :extra true}]]
    (let [{:keys [context calls]} (data/fixture {})]
      (is (= {:status 400 :body {:error "Recovery token is required"}}
             ((handler/restore context) (assoc request :body-params body))))
      (is (empty? @calls)))))

(deftest recovery-rejects-ciphertext-with-an-unknown-key-id
  (let [foreign (tempel/keychain-add-symmetric-key (tempel/keychain) :random {:key-id "foreign"})
        master (crypto/open {:key-id "master"})
        ciphertext (crypto-core/wrap foreign {:keychain (crypto-core/new-keychain)
                                              :associated-data (.getBytes "v-test" "UTF-8")})
        token (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                               (cbor/encode [1 "v-test" "master" ciphertext]))
        stored {:id "v-test"}
        {:keys [context state calls]} (data/fixture {:stored stored})
        response ((handler/restore (assoc context ::core/key-service master))
                  (assoc request :body-params {:token token}))]
    (is (= {:status 400 :body {:error "Invalid recovery token"}} response))
    (is (= stored @state))
    (is (empty? (data/calls-for calls {:operation :write})))))

(deftest recovery-rejects-malformed-keychain-envelopes
  (let [master (crypto/open {:key-id "master"})]
    (doseq [[description ciphertext]
            (tempel-data/malformed-envelopes {:key-protection master
                                              :associated-data (.getBytes "v-test" "UTF-8")})]
      (testing description
        (let [token (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                      (cbor/encode [1 "v-test" "master" ciphertext]))
              stored {:id "v-test"}
              {:keys [context state calls]} (data/fixture {:stored stored})
              response ((handler/restore (assoc context ::core/key-service master))
                        (assoc request :body-params {:token token}))]
          (is (= {:status 400 :body {:error "Invalid recovery token"}} response))
          (is (= stored @state))
          (is (empty? (data/calls-for calls {:operation :write}))))))))

(deftest exporting-a-recovery-token-preserves-http-error-statuses
  (doseq [[label options expected]
          [["no manage permission" {:permission :pull} {:status 403 :body {:error "Forbidden"}}]
           ["vault does not exist" {:stored nil} {:status 404 :body {:error "Vault not found"}}]
           ["recovery token cannot be exported after deletion" {:stored {:id "v-test"}}
            {:status 409 :body {:error "Vault is deleted"}}]]]
    (testing label
      (let [{:keys [context]} (data/fixture options)]
        (is (= expected ((handler/recovery-token context) request)))))))

(deftest recovery-distinguishes-invalid-tokens-from-key-service-and-unexpected-failures
  (doseq [[label unwrap expected]
          [["invalid ciphertext" (constantly nil) {:status 400 :body {:error "Invalid recovery token"}}]
           ["key service failed" #(throw (ex-info "Key service failed" {::anomaly/category ::anomaly/fault
                                                                        :reason ::keychain/key-service-failed}))
            {:status 500 :body {:error "Key service failed"}}]
           ["unexpected failure" #(throw (IllegalStateException. "Unexpected decrypt failure"))
            {:status 500 :body {:error "Unexpected decrypt failure"}}]]]
    (testing label
      (let [{:keys [context state calls]} (data/fixture {:unwrap unwrap})
            recovery ((handler/recovery-token context) request)]
        ((handler/delete context) request)
        (let [writes-before (count (data/calls-for calls {:operation :write}))]
          (is (= expected ((handler/restore context) (assoc request :body-params (:body recovery)))))
          (is (nil? (:ciphertext @state)))
          (is (= writes-before (count (data/calls-for calls {:operation :write})))))))))
