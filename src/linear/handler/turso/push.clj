(ns linear.handler.turso.push
  (:require
   [cognitect.anomalies :as anomaly]
   [integrant.core :as ig]
   [linear.handler.turso.hrana :as hrana]
   [linear.usecase.database :as database]
   [linear.usecase.database.evaluator :as evaluator]
   [malli.core :as m]
   [malli.transform :as mt])
  (:import
   (java.io FilterInputStream InputStream)
   (java.util Base64)))

(def ^:private ^:const max-statements 1000)
(def ^:private ^:const max-request-bytes (* 16 1024 1024))

(defn- invalid [message reason]
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

(defn- statement-from-wire-step [step]
  {:sql (get-in step [:stmt :sql])
   :parameters (mapv decode-wire-value (get-in step [:stmt :args]))})

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
          indexed-statements (->> (map-indexed (fn [index step]
                                                 [(inc index) step])
                                               body-steps)
                               (remove (fn [[_ step]]
                                         (hrana/sync-metadata-statement? (:stmt step))))
                               vec)]
      {:command {:statements (mapv (fn [[_ step]]
                                     (statement-from-wire-step step))
                                   indexed-statements)}
       :wire-indexes (mapv first indexed-statements)})))

(defn command [request]
  (:command (parse-command request)))

(defn- request-content-length [request]
  (let [value (get-in request [:headers "content-length"])]
    (cond
      (string? value) (try
                        (Long/parseLong value)
                        (catch NumberFormatException _
                          (invalid "Content-Length is invalid"
                                    ::invalid-content-length)))
      :else 0)))

(defn- request-too-large []
  (invalid "Push request is too large" ::request-too-large))

(defn- limited-input-stream [^InputStream input max-bytes]
  (let [read-bytes (atom 0)]
    (proxy [FilterInputStream] [input]
      (read
        ([]
         (let [result (.read input)]
           (if (= -1 result)
             -1
             (if (< @read-bytes max-bytes)
               (do (swap! read-bytes inc) result)
               (request-too-large)))))
        ([^bytes bytes]
         (.read ^InputStream this bytes 0 (alength bytes)))
        ([^bytes bytes ^long offset ^long length]
         (if (zero? length)
           0
           (let [remaining (- max-bytes @read-bytes)]
             (if (zero? remaining)
               (let [result (.read input)]
                 (if (= -1 result)
                   -1
                   (request-too-large)))
               (let [result (.read input bytes offset (min length remaining))]
                 (when (pos? result)
                   (swap! read-bytes + result))
                 result)))))))))

(defn- push-request? [request]
  (re-matches #"/d/[^/]+/v2/pipeline" (:uri request)))

(defn wrap-request-body-limit [handler {:keys [max-bytes]}]
  (fn [{:keys [body] :as request}]
    (try
      (handler (if (and (push-request? request)
                        (instance? InputStream body))
                 (assoc request :body (limited-input-stream body max-bytes))
                 request))
      (catch clojure.lang.ExceptionInfo error
        (if (= ::request-too-large (:reason (ex-data error)))
          (hrana/error-response error)
          (throw error))))))

(defmethod ig/init-key ::request-body-limit [_ _]
  #(wrap-request-body-limit % {:max-bytes max-request-bytes}))

(defn- handle-batch [context request batch]
  (let [{:keys [command wire-indexes]}
        (parse-command {:body-size (request-content-length request)
                        :batch batch})]
    (try
      (database/push! context {:actor (get-in request [:identity :sub])
                               :database-id (:id (:path-params request))
                               :command command})
      (hrana/batch-response (count (:steps batch)))
      (catch Exception error
        (if (hrana/statement-error? error)
          (hrana/batch-error-response batch {:error error :wire-indexes wire-indexes})
          (throw error))))))

(defn handler [context]
  (fn [{:keys [body-params] :as request}]
    (try
      (let [batch (hrana/single-batch body-params)]
        (if (hrana/last-change-id-query? batch)
          (do
            (database/check-permission context
                                       {:actor (get-in request [:identity :sub])
                                        :database-id (get-in request [:path-params :id])
                                        :permission :pull})
            (hrana/last-change-id-response))
          (handle-batch context request batch)))
      (catch Exception error
        (hrana/error-response error)))))
