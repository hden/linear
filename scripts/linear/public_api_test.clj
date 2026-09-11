(ns linear.public-api-test
  (:require
   [clj-kondo.core :as kondo]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn- findings
  ([source] (findings source {}))
  ([source config]
   (:findings
     (with-in-str source
       (kondo/run! {:lint ["-"]
                    :filename "src/fixture.clj"
                    :config-dir (.getCanonicalPath (io/file ".clj-kondo"))
                    :config config
                    :cache false})))))

(defn- api-findings [source]
  (filter #(= :linear/public-api (:type %)) (findings source)))

(deftest accepts-public-api-shapes-and-skips-non-public-definitions
  (is (empty?
        (api-findings
          "(ns fixture)
           (defn zero [] nil)
           (defn value [x] x)
           (defn operation [target arg-map] [target arg-map])
           (defn named [target {:as command}] [target command])
           (defn arities ([] nil) ([target {:keys [x]}] [target x]))
           (defn- private-operation [a b] [a b])
           (defn ^:private hidden [a b] [a b])
           (defn attributed {:private true} [a b] [a b])
           (defmacro macro-operation [a b] [a b])
           (defprotocol Capability (execute [target a b]))
           (defrecord Implementation [] Capability (execute [_ a b] [a b]))
           (private-operation 1 2)
           (hidden 1 2)
           (attributed 1 2)"))))

(deftest checks-every-arity-and-retains-standard-diagnostics
  (let [result (findings
                 "(ns fixture)
                  (defn positional [target value] [target value])
                  (defn mixed ([x] x) ([x y] [x y]) ([x y & more] [x y more]))
                  (defn unresolved [x] (missing x))")]
    (is (= 3 (count (filter #(= :linear/public-api (:type %)) result))))
    (is (some #(= :unresolved-symbol (:type %)) result))))

(deftest requires-an-exact-var-arity-and-nonblank-reason
  (let [source "(ns fixture)
                (defn value ([x y] [x y]) ([x y z] [x y z]))
                (defn other [x y] [x y])"
        result (findings source
                         {:linters {:linear/public-api
                                    {:exceptions {'[fixture/value 2] "Combines two natural values."
                                                  '[fixture/other 2] " "}}}})]
    (is (= 2 (count (filter #(= :linear/public-api (:type %)) result)))))
  (is (empty? (api-findings "(ns linear.spec) (defn valid? [schema value] [schema value])"))))

(deftest ignores-definitions-generated-by-the-existing-macro-hook
  (is (empty?
        (api-findings
          "(ns fixture (:require [labrador.core :as lab]))
           (lab/defretriever fetch-value {:tag :fixture} [a b c] [a b c])"))))
