(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def tracked
  (->> (file-seq (io/file "."))
       (filter #(.isFile %))
       (map #(.getPath %))
       (remove #(str/starts-with? % "./.git/"))))

(doseq [path (filter #(str/ends-with? % ".edn") tracked)]
  (edn/read-string (slurp path)))

  (let [misplaced (filter #(and (re-find #"\.(?:json|jsonld|bpmn)$" %)
                              (not (str/starts-with? % "./wire/"))
                              (not= % "./.well-known/did.json"))
                        tracked)
      forbidden (filter #(re-find #"(?:^|/)(?:go\.mod|go\.sum|.*\.go|run_tests\.sh)$" %) tracked)]
  (when (seq misplaced)
    (throw (ex-info "external formats must live under wire/" {:files misplaced})))
  (when (seq forbidden)
    (throw (ex-info "deprecated Go/shell artifacts found" {:files forbidden}))))

(println "EDN and artifact audit passed")
