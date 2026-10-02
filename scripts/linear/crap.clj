(ns linear.crap
  (:require
   [crap4clj.core :as core]
   [crap4clj.coverage :as coverage]
   [crap4clj.crap :as crap]))

(defn -main [limit]
  (let [limit     (Double/parseDouble limit)
        lcov-data (or (coverage/load-lcov "target/coverage/lcov.info")
                      (throw (ex-info "Run coverage before checking CRAP scores" {})))
        entries   (->> (core/find-source-files)
                       (mapcat #(core/analyze-file % lcov-data))
                       crap/sort-by-crap
                       vec)]
    (println (crap/format-report entries))
    (spit "target/coverage/crap.edn" (pr-str {:entries entries}))
    (when-not (and (seq entries)
                (every? #(and (number? (:crap %)) (<= (:crap %) limit)) entries))
      (throw (ex-info "CRAP score limit exceeded or scores unavailable"
                      {:limit limit :report "target/coverage/crap.edn"})))
    (println (format "Maximum CRAP score: %.4f (limit %.4f)"
                     (apply max (map :crap entries)) limit))))
