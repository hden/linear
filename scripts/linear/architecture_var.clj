(ns linear.architecture-var
  (:require
   [clojure.string :as string]))

(defn- layer [namespace]
  (second (string/split (str namespace) #"\.")))

(defn- var-rule [{:keys [from to]}]
  (let [from-layer (layer from)]
    (cond
      (and (= "adapter" from-layer)
           (= to 'labrador.core/fetch))
      [:adapter-fetch "adapters must return raw facts"]

      (and (= "usecase" from-layer)
           (= to 'labrador.core/defretriever))
      [:usecase-retriever "retrievers are adapter implementations"]

      :else nil)))

(defn violations [var-usages]
  (->> var-usages
       (remove :derived-name-location)
       (map (fn [{:keys [to name] :as usage}]
              (assoc usage :to (symbol (str to) (str name)))))
       (keep (fn [usage]
               (when-let [[rule message] (var-rule usage)]
                 (assoc usage :rule rule :message message))))
       (sort-by (juxt :filename :row #(str (:from %)) #(str (:to %))))
       vec))

(defn violations-for-paths [paths]
  (let [run!     (requiring-resolve 'clj-kondo.core/run!)
        analysis (:analysis (run! {:lint paths
                                   :config {:output {:analysis true}}}))]
    (violations (:var-usages analysis))))
