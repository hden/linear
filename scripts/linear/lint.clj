(ns linear.lint
  (:require
   [clj-kondo.core :as kondo]
   [linear.architecture :as architecture]
   [linear.policy :as policy]))

(defn -main []
  (let [entries (architecture/discover ["src"])
        analysis (policy/analyze-sources)
        application-var-usages (filter #(re-find #"(?:^|/)src/" (:filename %))
                                 (:var-usages analysis))
        violations (sort-by (juxt :filename :row :message)
                            (concat (:violations analysis)
                                    (architecture/violations entries {:var-usages application-var-usages})))
        lint-result (kondo/run! {:lint ["src" "test" "scripts"]
                                 :cache false
                                 :repro true})
        {:keys [error warning]} (:summary lint-result)]
    (doseq [violation violations]
      (println (policy/format-violation violation)))
    (kondo/print! lint-result)
    (when (or (seq violations) (pos? error) (pos? warning))
      (System/exit 1))))
