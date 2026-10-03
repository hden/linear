(ns linear.architecture
  (:require
   [clojure.core.match :refer [match]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.namespace.file :as namespace-file]
   [clojure.tools.namespace.find :as namespace-find]
   [clojure.tools.namespace.parse :as namespace-parse]
   [linear.policy :as policy]))

(def ^:private approved-exceptions #{})

(defn- linear-namespace? [namespace]
  (string/starts-with? (str namespace) "linear."))

(defn- layer [namespace]
  (second (string/split (str namespace) #"\.")))

(defn- adapter-technology [namespace]
  (when (= "adapter" (layer namespace))
    (nth (string/split (str namespace) #"\.") 2 nil)))

(defn- core-namespace? [namespace]
  (string/ends-with? (str namespace) ".core"))

(defn- dependency-rule [{:keys [from to]}]
  (let [relationship
        {:spec? (= to 'linear.spec)
         :from-layer (layer from)
         :to-layer (layer to)
         :same-technology? (= (adapter-technology from)
                              (adapter-technology to))
         :from-core? (core-namespace? from)
         :to-core? (core-namespace? to)}
        rule
        (match relationship
          {:spec? true} nil
          {:from-layer "handler" :to-layer "handler"} nil
          {:from-layer "handler" :to-layer "usecase"} nil
          {:from-layer "usecase" :to-layer "usecase"} nil
          {:from-layer "adapter" :to-layer "usecase"} nil
          {:from-layer "adapter"
           :to-layer "adapter"
           :same-technology? false}
          [:cross-adapter "cross-adapter dependency"]
          {:from-layer "adapter"
           :to-layer "adapter"
           :same-technology? true
           :from-core? true
           :to-core? false}
          [:core-direction "core namespace must depend inward"]
          {:from-layer "adapter"
           :to-layer "adapter"
           :same-technology? true} nil
          :else [:layer-direction "namespace dependency is not allowed"])]
    (when-not (contains? approved-exceptions [from to])
      rule)))

(defn- source-entry [file]
  (let [declaration (namespace-file/read-file-ns-decl file)]
    (when-not declaration
      (throw (ex-info "Clojure source has no namespace declaration"
                      {:filename (.getPath ^java.io.File file)})))
    (let [namespace (namespace-parse/name-from-ns-decl declaration)]
      (when-not (re-matches #"linear\.(?:spec|(?:handler|usecase|adapter|middleware|server)(?:\..+)?)"
                  (str namespace))
        (throw (ex-info "Source namespace has no approved architectural role"
                        {:filename (.getPath ^java.io.File file)
                         :namespace namespace})))
      {:namespace namespace
       :dependencies (namespace-parse/deps-from-ns-decl declaration)
       :filename (.getPath ^java.io.File file)})))

(defn discover [directories]
  (->> directories
       (map io/file)
       (mapcat namespace-find/find-sources-in-dir)
       (map source-entry)
       (sort-by (comp str :namespace))
       vec))

(defn- dependency-usages [entries]
  (into []
        (comp
          (mapcat (fn [{:keys [namespace dependencies filename row]}]
                    (map (fn [dependency]
                           {:from namespace
                            :to dependency
                            :filename filename
                            :row (or row 1)})
                         dependencies)))
          (filter #(and (linear-namespace? (:from %))
                        (linear-namespace? (:to %)))))
        entries))

(defn- adjacency [usages]
  (reduce (fn [graph {:keys [from to]}]
            (update graph from (fnil conj #{}) to))
          {}
          usages))

(defn- cycle-path [graph from to]
  (letfn [(search [node path seen]
            (some (fn [next-node]
                    (cond
                      (= next-node from) (conj path next-node)
                      (seen next-node) nil
                      :else (search next-node
                                    (conj path next-node)
                                    (conj seen next-node))))
                  (sort-by str (get graph node))))]
    (when-let [path (search to [from to] #{from to})]
      (let [cycle      (vec (butlast path))
            rotations (map #(concat (subvec cycle %) (subvec cycle 0 %))
                           (range (count cycle)))
            canonical (first (sort-by #(mapv str %) rotations))]
        (conj (vec canonical) (first canonical))))))

(defn- violation [usage rule message]
  (assoc usage :rule rule :message message))

(defn violations
  ([entries] (violations entries {}))
  ([entries {:keys [var-usages]}]
   (let [var-dependencies (->> var-usages
                            (filter #(and (linear-namespace? (:from %))
                                          (linear-namespace? (:to %))
                                          (not= (:from %) (:to %))))
                            (map #(select-keys % [:filename :row :from :to])))
         dependency-usages (distinct (concat (dependency-usages entries) var-dependencies))
         graph             (adjacency dependency-usages)]
     (->> (concat
            (keep (fn [usage]
                    (when-let [[rule message] (dependency-rule usage)]
                      (violation usage rule message)))
                  dependency-usages)
            (keep (fn [{:keys [from to] :as usage}]
                    (when-let [path (cycle-path graph from to)]
                      (violation usage
                                 :cycle
                                 (str "dependency cycle: "
                                      (string/join " -> " path)))))
                  dependency-usages))
          (sort-by (juxt :filename :row #(str (:from %)) #(str (:to %)) :message))
          vec))))

(defn -main []
  (let [entries        (discover ["src"])
        source-analysis (policy/analyze-sources {:paths ["src"]})
        failures (violations entries {:var-usages (:var-usages source-analysis)})]
    (doseq [failure failures]
      (println (policy/format-violation failure)))
    (when (seq failures)
      (System/exit 1))))
