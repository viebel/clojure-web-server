(defproject clojure-web-server "0.1.0"
  :dependencies [[org.clojure/clojure            "1.10.0"]
                 [com.stuartsierra/component     "0.3.2"]
                 [com.stuartsierra/component.repl "0.2.0"]
                 [com.google.guava/guava "19.0"]
                 [com.novemberain/monger "3.1.0"]
                 [funcool/catacumba "2.2.2"]]

  :target-path "target/%s"
  :uberjar-name "clojure-web-server-standalone.jar"
  :source-paths ["src/clj"]
  :test-paths ["test/clj" "src/clj"]

  :profiles {:uberjar {:aot          :all
                       :main         ^:skip-aot clojure-web-server.core
                       :dependencies [[org.clojure/tools.nrepl "0.2.13"]]}
             :dev     {:dependencies [[org.clojure/tools.namespace "0.3.0-alpha4"]]
                       :jvm-opts     ["-Dclojure.server.repl={:port 5555 :accept clojure.core.server/repl}"]
                       :source-paths ["dev"]}}

  :repl-options {:init-ns user
                 :init    (set! *print-length* 100)}

  :clean-targets ^{:protect false} [:target-path])
