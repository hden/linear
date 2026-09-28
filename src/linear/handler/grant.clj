(ns linear.handler.grant
  (:require
   [cognitect.anomalies :as anomaly]
   [linear.usecase.grant :as grant]))

(def ^:private permissions
  {"pull" :pull
   "push" :push
   "manage" :manage})

(defn- error-response [error]
  (let [category (::anomaly/category (ex-data error))]
    {:status (case category
               ::anomaly/incorrect 400
               ::anomaly/forbidden 403
               500)
     :body {:error (if (= ::anomaly/forbidden category)
                     "Forbidden"
                     (.getMessage ^Exception error))}}))

(defn- actor [request]
  (get-in request [:identity :sub]))

(defn list-grants [context]
  (fn [request]
    (try
      (let [grants (grant/list-grants context
                                      {:actor (actor request)
                                       :vault-id (get-in request [:path-params :id])})]
        {:status 200
         :body {:grants (mapv #(update % :permission name) grants)}})
      (catch Exception error
        (error-response error)))))

(defn set-grant [context]
  (fn [{:keys [body-params path-params] :as request}]
    (try
      (let [permission (when (and (map? body-params)
                                  (= #{:permission} (set (keys body-params))))
                         (permissions (:permission body-params)))]
        (when-not permission
          (throw (ex-info "Grant permission is invalid"
                          {::anomaly/category ::anomaly/incorrect
                           :reason ::invalid-permission})))
        (grant/set-grant! context
                          {:actor (actor request)
                           :vault-id (:id path-params)
                           :subject (:subject path-params)
                           :permission permission})
        {:status 204})
      (catch Exception error
        (error-response error)))))

(defn revoke-grant [context]
  (fn [{:keys [path-params] :as request}]
    (try
      (grant/revoke-grant! context
                           {:actor (actor request)
                            :vault-id (:id path-params)
                            :subject (:subject path-params)})
      {:status 204}
      (catch Exception error
        (error-response error)))))
