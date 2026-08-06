(ns yotei.edge.inline
  "Compile-time resource inlining for the Worker build.

  `yotei.render` reads `jp_go_dds/dds.css` at runtime with `slurp`, which is
  right on the JVM and impossible in a Worker — there is no filesystem, and the
  stylesheet lives in a git dependency rather than in this repo.

  So the Worker gets it as a string literal, read from the classpath while
  shadow-cljs is compiling. One source of truth (the dependency's resource) and
  one copy in the bundle, rather than a vendored duplicate that drifts the next
  time jp-go-dds is bumped."
  (:require [clojure.java.io :as io]))

(defmacro inline-resource
  "The contents of classpath resource `path`, as a literal.

  Throws at compile time if it is missing. Returning nil would ship a Worker
  that serves every page unstyled, and an unstyled page looks like a CSS bug
  rather than a build one."
  [path]
  (if-let [r (io/resource path)]
    (slurp r)
    (throw (ex-info (str "resource not on the classpath: " path
                         " — the Worker cannot be built without it")
                    {:path path}))))
