(ns hooks.linear.public-api
  (:require
   [clj-kondo.hooks-api :as api]
   [clojure.string :as string]))

(defn- arguments [tail]
  (if (api/vector-node? (first tail))
    [(first tail)]
    (keep #(when (api/list-node? %) (first (:children %))) tail)))

(defn- allowed? [args]
  (let [values (api/sexpr args)]
    (and (not-any? #{'&} values)
         (or (<= (count values) 1)
             (and (= 2 (count values))
                  (or (map? (second values))
                      (= 'arg-map (second values))))))))

(defn check-definition [{:keys [node ns config]}]
  (let [[_ name-node & tail] (:children node)
        tail (if (api/string-node? (first tail)) (rest tail) tail)
        attributes (when (api/map-node? (first tail)) (api/sexpr (first tail)))
        tail (if attributes (rest tail) tail)
        var-name (api/sexpr name-node)
        qualified-var (symbol (str ns) (str var-name))
        exceptions (get-in config [:linters :linear/public-api :exceptions])]
    (when-not (or (api/generated-node? node)
                  (:private (meta var-name))
                  (:private attributes))
      (doseq [args (arguments tail)
              :let [values (api/sexpr args)
                    amp-index (first (keep-indexed (fn [index value] (when (= '& value) index)) values))
                    arity (if (nil? amp-index) (count values) [:variadic amp-index])
                    reason (get exceptions [qualified-var arity])]
              :when (and (not (allowed? args))
                         (not (and (string? reason) (not (string/blank? reason)))))]
        (api/reg-finding!
          (assoc (meta args)
                 :type :linear/public-api
                 :message (str qualified-var " " arity
                               " must use [target arg-map] or have a reasoned arity exception")))))
    {:node node}))
