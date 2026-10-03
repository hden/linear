(ns linear.policy
  (:require
   [clj-kondo.core :as kondo]
   [clj-kondo.hooks-api :as api]
   [clojure.java.io :as io]
   [clojure.string :as string]))

(def ^:private forbidden-vars
  '#{clojure.core/with-redefs clojure.core/with-redefs-fn
     clojure.core/alter-var-root clojure.core/intern clojure.core/ns-unmap
     cljs.core/with-redefs cljs.core/with-redefs-fn
     cljs.core/alter-var-root cljs.core/intern cljs.core/ns-unmap})

(def ^:private dynamic-vars
  '#{clojure.core/eval clojure.core/load clojure.core/load-string clojure.core/load-file
     clojure.core/resolve clojure.core/ns-resolve clojure.core/requiring-resolve
     clojure.core/find-var clojure.core/require clojure.core/use
     cljs.core/eval cljs.core/resolve cljs.core/ns-resolve})

(def ^:private approved-dynamic-references {})

(def ^:private root-mutating-methods
  '#{.bindRoot .alterRoot .unbindRoot
     bindRoot alterRoot unbindRoot clojure.lang.Var/intern})

(def ^:private source-directories ["src" "test" "scripts" ".clj-kondo/hooks"])

(defn- layer [namespace]
  (second (string/split (str namespace) #"\.")))

(defn- var-rule [{:keys [from from-var to filename]}]
  (let [from-layer (layer from)
        application-source? (boolean (re-find #"(?:^|/)src/" filename))]
    (cond
      (forbidden-vars to)
      [:global-var-mutation "global Var replacement is forbidden; provide a capability instead"]

      (and (dynamic-vars to)
           (not (contains? approved-dynamic-references
                           [(when from-var (symbol (str from) (str from-var))) to])))
      [:dynamic-reference "dynamic code or name resolution requires an exact reasoned approval"]

      (and application-source? (= "adapter" from-layer)
           (= to 'labrador.core/fetch))
      [:adapter-fetch "adapters must return raw facts"]

      (and application-source? (= "usecase" from-layer)
           (= to 'labrador.core/defretriever))
      [:usecase-retriever "retrievers are adapter implementations"]

      :else nil)))

(defn violations [var-usages]
  (->> var-usages
       (map (fn [{:keys [to name] :as usage}]
              (assoc usage :to (symbol (str to) (str name)))))
       (keep (fn [usage]
               (when-let [[rule message] (var-rule usage)]
                 (assoc usage :rule rule :message message))))
       (sort-by (juxt :filename :row #(str (:from %)) #(str (:to %))))
       vec))

(defn- source-files [paths]
  (when (empty? paths)
    (throw (ex-info "Policy inspection requires source paths" {})))
  (let [files (mapcat
                (fn [path]
                  (let [file (io/file path)]
                    (when-not (.exists file)
                      (throw (ex-info "Policy source path is missing" {:filename (str path)})))
                    (let [sources (filter #(and (.isFile ^java.io.File %)
                                                (re-find #"\.(clj|cljs|cljc)$" (.getName ^java.io.File %)))
                                          (file-seq file))]
                      (when (empty? sources)
                        (throw (ex-info "Policy source path has no Clojure sources" {:filename (str path)})))
                      sources)))
                paths)]
    (vec (sort-by str (distinct files)))))

(defn- script-test-violations [{:keys [files namespace-definitions namespace-usages]}]
  (let [script-namespaces (into #{}
                            (comp (filter #(re-find #"(?:^|/)scripts/" (:filename %)))
                                  (map :name))
                            namespace-definitions)]
    (concat
      (for [file files
            :when (and (re-find #"(?:^|/)scripts/" (str file))
                       (re-find #"(?:_test|_spec)\.(clj|cljs|cljc)$" (str file)))]
        {:filename (str file) :row 1 :from 'source :to 'test
         :rule :script-test :message "tests for scripts are forbidden, regardless of placement"})
      (for [{:keys [filename to] :as usage} namespace-usages
            :when (or (and (re-find #"(?:^|/)test/" filename) (script-namespaces to))
                      (and (re-find #"(?:^|/)scripts/" filename)
                           (#{'clojure.test 'cljs.test} to)))]
        (assoc usage :rule :script-test
               :message "tests must exercise application boundaries, not verification scripts")))))

(defn- node-violations [file]
  (let [filename (str file)
        root (try
               (api/parse-string (str "[\n" (slurp file) "\n]"))
               (catch Exception error
                 (throw (ex-info "Policy source cannot be parsed" {:filename filename} error))))]
    (mapcat
      (fn [node]
        (let [row (max 1 (dec (:row (meta node))))
              finding (fn [value rule message]
                        {:filename filename :row row :from 'source :to value :rule rule :message message})]
          (concat
            (when (and (api/token-node? node) (root-mutating-methods (api/sexpr node)))
              [(finding (api/sexpr node) :global-var-mutation "direct Var root mutation is forbidden")])
            (when (api/map-node? node)
              (for [key-node (take-nth 2 (:children node))
                    :when (and (api/keyword-node? key-node)
                               (#{:clj-kondo/ignore :clj-kondo/config} (api/sexpr key-node)))]
                (finding (api/sexpr key-node) :policy-suppression "inline lint suppression or configuration is forbidden")))
            (when (and (re-find #"(?:^|/)test/" filename)
                       (api/string-node? node)
                       (re-find #"(?:scripts/|\.codex/hooks/|\.clj-kondo/hooks/)" (api/sexpr node)))
              [(finding 'script :script-test "tests for scripts or hooks are forbidden")]))))
      (tree-seq #(and (not (api/quote-node? %)) (seq (:children %))) :children root))))

(defn analyze-sources
  ([]
   (when (some #(.isFile ^java.io.File %) (file-seq (io/file "test/scripts")))
     (throw (ex-info "Tests for scripts are forbidden" {:filename "test/scripts"})))
   (analyze-sources {:paths source-directories}))
  ([{:keys [paths]}]
   (let [files (source-files paths)
         syntax-violations (mapcat node-violations files)
         {:keys [analysis findings]}
         (kondo/run! {:lint (mapv str files)
                      :cache false
                      :repro true
                      ;; Repository hooks and suppression settings must not erase policy evidence.
                      :config ^:replace {:output {:analysis true}
                                         :skip-comments false
                                         :linters {:syntax {:level :error}}}})]
     (when (or (not (vector? (:var-usages analysis)))
               (not (seq (:namespace-definitions analysis)))
               (some #(= :syntax (:type %)) findings))
       (throw (ex-info "Policy analysis is incomplete or invalid" {:findings findings})))
     (assoc analysis :violations
            (->> (concat syntax-violations
                         (script-test-violations (assoc analysis :files files))
                         (violations (:var-usages analysis)))
                 distinct
                 (sort-by (juxt :filename :row :message))
                 vec)))))

(defn format-violation [{:keys [filename row rule from to message]}]
  (str filename ":" row ": " (name rule) ": " from " -> " to ": " message))

(defn -main []
  (let [failures (:violations (analyze-sources))]
    (doseq [failure failures]
      (println (format-violation failure)))
    (when (seq failures)
      (System/exit 1))))
