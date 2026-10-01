(ns linear.handler.turso.request
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.usecase.database.evaluator :as evaluator]
   [malli.core :as m]
   [malli.transform :as mt])
  (:import
   (java.util Base64)))

(def ^:private ^:const max-statements 1000)
(def ^:const max-request-bytes (* 16 1024 1024))

(defn- invalid [message reason]
  (throw (ex-info message
                  {::anomaly/category ::anomaly/incorrect
                   ::anomaly/message message
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

(defn- statement-from-wire-step [step]
  {:sql (get-in step [:stmt :sql])
   :parameters (mapv decode-wire-value (get-in step [:stmt :args]))})

(def ^:private progress-upsert
  "INSERT INTO turso_sync_last_change_id(client_id, pull_gen, change_id) VALUES (?, ?, ?) ON CONFLICT(client_id) DO UPDATE SET pull_gen=excluded.pull_gen, change_id=excluded.change_id")

(def ^:private valid-progress-parameters?
  (m/validator [:tuple [:string {:min 1}] [:int {:min 0}] [:int {:min 0}]]))

(defn metadata-client-id [{:as batch}]
  (let [statement (get-in batch [:steps 0 :stmt])
        args (:args statement)]
    (when-not (and (vector? args)
                   (= 1 (count args))
                   (= "text" (:type (first args)))
                   (valid-wire-value? (first args))
                   (seq (:value (first args)))
                   (nil? (:sql_id statement))
                   (or (nil? (:named_args statement)) (= [] (:named_args statement)))
                   (nil? (get-in batch [:steps 0 :condition])))
      (invalid "Sync metadata query is invalid" ::invalid-sync-metadata-query))
    (:value (first args))))

(defn- command-progress [statements]
  (let [indexes (keep-indexed (fn [index statement]
                                (when (= progress-upsert (:sql statement))
                                  index))
                  statements)]
    (when (seq indexes)
      (when-not (and (= 1 (count indexes))
                     (= (first indexes) (dec (count statements))))
        (invalid "Sync progress must be the final statement" ::invalid-sync-progress))
      (let [parameters (:parameters (peek statements))]
        (when-not (valid-progress-parameters? parameters)
          (invalid "Sync progress is invalid" ::invalid-sync-progress))
        (let [[client-id generation change-id] parameters]
          {:client-id client-id :generation generation :change-id change-id})))))

(defn- validate-limits [body-size steps]
  (when-not (valid-request-size? body-size)
    (invalid "Push request is too large"
              ::request-too-large))
  (when-not (valid-step-count? steps)
    (invalid "Push has too many statements"
              ::too-many-statements)))

(defn- validate-batch [batch]
  (when-not (valid-batch? batch)
    (invalid "Push batch is not canonical" ::invalid-push-batch))
  (doseq [step (-> batch :steps pop rest)
          value (get-in step [:stmt :args])]
    (when-not (valid-wire-value? value)
      (invalid "Push argument is invalid" ::invalid-value))))

(defn parse-command
  {:malli/schema [:->
                  [:map
                   [:body-size nat-int?]
                   [:batch :map]]
                  [:map
                   [:command ::evaluator/push-command]
                   [:wire-indexes [:vector nat-int?]]]]}
  [{:keys [body-size batch]}]
  (let [steps (:steps batch)]
    (validate-limits body-size steps)
    (validate-batch batch)
    (let [last-index (dec (count steps))
          body-steps (subvec steps 1 last-index)
          statements (mapv statement-from-wire-step body-steps)
          progress (command-progress statements)]
      {:command (cond-> {:statements statements}
                  progress (assoc :sync-progress progress))
       :wire-indexes (vec (range 1 last-index))})))

(def ^:private last-change-id-query
  "SELECT pull_gen, change_id FROM turso_sync_last_change_id WHERE client_id = ?")

(defn last-change-id-query? [{:as batch}]
  (let [statement (get-in batch [:steps 0 :stmt])]
    (and (vector? (:steps batch))
         (= 1 (count (:steps batch)))
         (= last-change-id-query (:sql statement))
         (= true (:want_rows statement)))))

(defn single-batch [{:as pipeline}]
  (let [requests (:requests pipeline)
        request (when (sequential? requests) (first requests))]
    (if (and (nil? (:baton pipeline))
             (sequential? requests)
             (= 1 (count requests))
             (= "batch" (:type request))
             (map? (:batch request)))
      (:batch request)
      (invalid "Push pipeline must contain exactly one batch" ::invalid-pipeline))))
