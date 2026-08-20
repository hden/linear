(ns linear.test
  (:require
   [clojure.java.io :as io]
   [duct.test :as duct-test]
   [integrant.core :as ig]))

(declare read-config)

(defn- include-file [^String filepath]
  (let [file (io/file filepath)]
    (if (.exists file)
      (read-config file)
      (throw (ex-info (format "Unable to find include file: #duct/include \"%s\""
                              filepath)
                      {:include file})))))

(defn- read-config [^java.io.File file]
  (ig/read-string {:readers {'duct/include include-file
                             'duct/resource io/resource}}
                  (slurp file)))

(defn- load-config []
  (let [file (io/file "duct.edn")]
    (if (.exists file)
      (read-config file)
      {})))

(defn- resolve-migrations [config]
  (let [path       [:system :duct.module/sql]
        sql-config (get-in config path)]
    (if (and sql-config (not (contains? sql-config :migrations)))
      (assoc-in config path
                (assoc sql-config :migrations
                       (get-in (load-config) (conj path :migrations))))
      config)))

(defn run
  "Run the Duct application in a test environment. Supports the following
  options:

  - `:config`   - the config map to use (default loads from `duct.edn`)
  - `:keys`     - only run this collection of keys (default is all keys)
  - `:profiles` - use these profiles (default is `[:test]`)
  - `:vars`     - bind vars to this map of symbols to values"
  ([] (run {}))
  ([options]
   (duct-test/run (update options :config
                          #(resolve-migrations (or % {}))))))

(defn catch-ex-data [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo ex
      (ex-data ex))))
