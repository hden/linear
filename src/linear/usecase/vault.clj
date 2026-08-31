(ns linear.usecase.vault
  (:require
   [hden.ulid :refer [ulid]]
   [labrador.core :as lab]
   [linear.usecase.core :as core]
   [urania.core :as u]))

(defprotocol Transactable
  (-transact [ds f arg-map]))

(defmacro with-transaction
  [[binding ds & [arg-map]] & body]
  `(-transact ~ds
              (fn [~binding]
                ~@body)
              ~(or arg-map {})))

(defprotocol Database
  (-create! [tx arg-map]))

(defn vault-id []
  (str "v-" (ulid)))

(defn- fetch [tx ids]
  (u/run!! (lab/traverse (into {}
                               (map (fn [id]
                                      [id (lab/fetch ::vault id)]))
                               ids))
           {:env {:tx tx}}))

(defn create!
  {:malli/schema [:->
                  ::core/context
                  [:map
                   [:data [:sequential [:map]]]
                   [:idempotency-key :string]]
                  [:map-of :string :map]]}
  [context {:keys [data idempotency-key]}]
  (with-transaction [tx (core/vault-datasource context)]
    ;; TODO: verify ownership
    (let [vaults (map #(assoc % :id (vault-id)) data)
          ;; idempotent writes
          ids (-create! tx {:data vaults :idempotency-key idempotency-key})]
      ;; read your writes
      (fetch tx ids))))

(defn get-by-ids
  {:malli/schema [:->
                  ::core/context
                  [:map
                   [:ids [:sequential :string]]]
                  [:map-of :string :map]]}
  [context {:keys [ids]}]
  (with-transaction [tx (core/vault-datasource context) {:read-only true}]
    ;; TODO: verify ownership
    (fetch tx ids)))
