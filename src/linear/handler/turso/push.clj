(ns linear.handler.turso.push
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.usecase.database :as database]
   [malli.core :as m]
   [malli.transform :as mt])
  (:import
   (java.util Base64)))

(def ^:private ^:const max-statements 1000)
(def ^:private ^:const max-request-bytes (* 16 1024 1024))

(defn- invalid! [message reason]
  (throw (ex-info message
                  {::anomaly/category ::anomaly/incorrect
                   :reason reason})))

(defn- valid-integer? [value]
  (try
    (Long/parseLong ^String value)
    true
    (catch NumberFormatException _
      false)))

(defn- valid-base64? [value]
  (try
    (.decode (Base64/getDecoder) ^String value)
    true
    (catch IllegalArgumentException _
      false)))

(defn- decode-value [{:keys [type value base64]}]
  (case type
    "null" nil
    "integer" (Long/parseLong value)
    "float" value
    "text" value
    "blob" (.decode (Base64/getDecoder) ^String base64)))

(defn- statement-schema [sql-schema args-schema]
  [:map {:closed true}
   [:sql sql-schema]
   [:sql_id :nil]
   [:args args-schema]
   [:named_args [:vector {:max 0} :any]]
   [:want_rows [:= false]]
   [:replication_index :nil]])

(def ^:private hrana-transformer
  (mt/transformer {:name :hrana}))

(let [condition-schema
      [:map {:closed true}
       [:type [:= "not"]]
       [:cond [:map {:closed true}
               [:type [:= "is_autocommit"]]]]]

      value-schema
      [:multi {:dispatch :type
               :decode/hrana {:leave decode-value}}
       ["null" [:map {:closed true}
                [:type [:= "null"]]]]
       ["integer" [:map {:closed true}
                   [:type [:= "integer"]]
                   [:value [:and :string [:fn valid-integer?]]]]]
       ["float" [:map {:closed true}
                 [:type [:= "float"]]
                 [:value [:and :double [:fn #(Double/isFinite ^double %)]]]]]
       ["text" [:map {:closed true}
                [:type [:= "text"]]
                [:value :string]]]
       ["blob" [:map {:closed true}
                [:type [:= "blob"]]
                [:base64 [:and :string [:fn valid-base64?]]]]]]

      batch-schema
      [:map {:closed true
             :registry {::condition condition-schema
                        ::begin-step
                        [:map {:closed true}
                         [:condition :nil]
                         [:stmt (statement-schema [:= "BEGIN IMMEDIATE"]
                                                  [:vector {:max 0} :any])]]
                        ::body-step
                        [:map {:closed true}
                         [:condition ::condition]
                         [:stmt (statement-schema :string [:vector :map])]]
                        ::commit-step
                        [:map {:closed true}
                         [:condition ::condition]
                         [:stmt (statement-schema [:= "COMMIT"]
                                                  [:vector {:max 0} :any])]]}}
       [:steps [:cat
                ::begin-step
                [:repeat {:min 0 :max max-statements} ::body-step]
                ::commit-step]]
       [:replication_index :nil]]]
  (def ^:private valid-batch? (m/validator batch-schema))
  (def ^:private valid-wire-value? (m/validator value-schema))
  (def ^:private decode-wire-value (m/decoder value-schema hrana-transformer)))

(def ^:private valid-request-size?
  (m/validator [:int {:min 0 :max max-request-bytes}]))

(def ^:private valid-step-count?
  (m/validator [:vector {:max (+ max-statements 2)} :any]))

(defn- body-statement [step]
  {:sql (get-in step [:stmt :sql])
   :parameters (mapv decode-wire-value (get-in step [:stmt :args]))})

(defn- validate-limits! [body-size steps]
  (when-not (valid-request-size? body-size)
    (invalid! "Push request is too large"
              ::request-too-large))
  (when-not (valid-step-count? steps)
    (invalid! "Push has too many statements"
              ::too-many-statements)))

(defn- validate-batch! [batch]
  (when-not (valid-batch? batch)
    (invalid! "Push batch is not canonical" ::invalid-push-batch))
  (doseq [step (-> batch :steps pop rest)
          value (get-in step [:stmt :args])]
    (when-not (valid-wire-value? value)
      (invalid! "Push argument is invalid" ::invalid-value))))

(defn ->command
  {:malli/schema [:->
                  [:map
                   [:body-size nat-int?]
                   [:batch :map]]
                  ::database/push-command]}
  [{:keys [body-size batch]}]
  (let [steps (:steps batch)]
    (validate-limits! body-size steps)
    (validate-batch! batch)
    (let [last-index (dec (count steps))
          body       (subvec steps 1 last-index)]
      {:statements (mapv body-statement body)})))
