(ns linear.architecture-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :as test :refer [deftest is]]
   [linear.architecture :as architecture]
   [linear.architecture-var :as architecture-var]))

(defn- dependency [from to row]
  {:namespace from
   :dependencies #{to}
   :filename (str "src/" (munge from) ".clj")
   :row row})

(deftest accepts-structural-dependencies
  (is (empty? (architecture/violations
                [(dependency 'linear.handler.health 'linear.usecase.healthcheck 1)
                 (dependency 'linear.handler.turso.pull 'linear.handler.turso.protobuf 2)
                 (dependency 'linear.usecase.vault 'linear.usecase.core 3)
                 (dependency 'linear.adapter.postgres.vault 'linear.adapter.postgres.core 4)
                 (dependency 'linear.adapter.postgres.vault 'linear.usecase.vault 5)
                 (dependency 'linear.adapter.sqlite.ffi 'linear.spec 6)]))))

(deftest rejects-structural-violations
  (let [violations
        (architecture/violations
          [(dependency 'linear.handler.health 'linear.adapter.postgres.datasource 7)
           (dependency 'linear.usecase.database 'linear.handler.turso.pull 9)
           (dependency 'linear.adapter.postgres.vault 'linear.adapter.crypto.tempel 5)
           (dependency 'linear.adapter.postgres.core 'linear.adapter.postgres.vault 4)])]
    (is (= ["src/linear.adapter.postgres.core.clj:4: linear.adapter.postgres.core -> linear.adapter.postgres.vault: core namespace must depend inward"
            "src/linear.adapter.postgres.vault.clj:5: linear.adapter.postgres.vault -> linear.adapter.crypto.tempel: cross-adapter dependency"
            "src/linear.handler.health.clj:7: linear.handler.health -> linear.adapter.postgres.datasource: namespace dependency is not allowed"
            "src/linear.usecase.database.clj:9: linear.usecase.database -> linear.handler.turso.pull: namespace dependency is not allowed"]
           (mapv architecture/format-violation violations)))))

(deftest rejects-cycles-with-a-stable-diagnostic
  (let [entries [(dependency 'linear.adapter.sqlite.core 'linear.adapter.sqlite.file 2)
                 (dependency 'linear.adapter.sqlite.file 'linear.adapter.sqlite.core 8)]]
    (is (= ["src/linear.adapter.sqlite.core.clj:2: linear.adapter.sqlite.core -> linear.adapter.sqlite.file: dependency cycle: linear.adapter.sqlite.core -> linear.adapter.sqlite.file -> linear.adapter.sqlite.core"
            "src/linear.adapter.sqlite.file.clj:8: linear.adapter.sqlite.file -> linear.adapter.sqlite.core: dependency cycle: linear.adapter.sqlite.core -> linear.adapter.sqlite.file -> linear.adapter.sqlite.core"]
           (->> (architecture/violations entries)
                (filter #(= :cycle (:rule %)))
                (mapv architecture/format-violation))))))

(deftest analyzes-forbidden-vars-from-source
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                             "linear-architecture"
                             (make-array java.nio.file.attribute.FileAttribute 0)))
        adapter   (io/file directory "linear/adapter/example.clj")
        usecase   (io/file directory "linear/usecase/example.clj")]
    (.mkdirs (.getParentFile adapter))
    (.mkdirs (.getParentFile usecase))
    (spit adapter
          "(ns linear.adapter.example (:require [labrador.core :as lab]))\n(def value (lab/fetch :example :id))\n")
    (spit usecase
          "(ns linear.usecase.example (:require [labrador.core :as lab]))\n(lab/defretriever example {:tag :example} [_ _] {})\n")
    (let [violations (architecture-var/violations-for-paths
                       [(.getPath directory)])]
      (is (= #{['linear.adapter.example
                'labrador.core/fetch
                :adapter-fetch]
               ['linear.usecase.example
                'labrador.core/defretriever
                :usecase-retriever]}
             (into #{}
                   (map (juxt :from :to :rule))
                   violations))))))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'linear.architecture-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
