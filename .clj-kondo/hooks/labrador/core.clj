(ns hooks.labrador.core
  (:require [clj-kondo.hooks-api :as api]))

(defn- attr-map? [m]
  (and (map? m)
       (not-any? #(contains? m %)
                 [:decorate-fn :tag])))

(defn defretriever
  [{:keys [node]}]
  (let [children           (:children node)
        [_ name-node & tail-nodes] children
        parts              (map (fn [n] {:node n :sexpr (api/sexpr n)}) tail-nodes)
        step               (fn [pred {:keys [node sexpr]}]
                             (when (and node (pred sexpr)) node))
        doc-node           (step string? (first parts))
        parts              (if doc-node (rest parts) parts)
        attr-node          (step attr-map? (first parts))
        parts              (if attr-node (rest parts) parts)
        opts-entry         (when (and (seq parts) (map? (:sexpr (first parts))))
                             (first parts))
        parts              (if opts-entry (rest parts) parts)
        options            (some-> opts-entry :sexpr)
        fn-nodes           (mapv :node parts)
        ns-name            (or (some-> node meta :ns) 'user)
        default-tag        (keyword (str ns-name) (name (api/sexpr name-node)))
        tag                 (get options :tag default-tag)
        defn-children       (vec (concat [(api/token-node 'defn) name-node]
                                         (keep identity [doc-node attr-node])
                                         fn-nodes))
        defmethod-children  [(api/token-node 'defmethod)
                             (api/token-node 'labrador.core/fetch)
                             (api/keyword-node tag)
                             (api/vector-node [])
                             name-node]]
    {:node (api/list-node
            [(api/token-node 'do)
             (api/list-node defn-children)
             (api/list-node defmethod-children)])}))
