(defproject clojure-web-server "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure            "1.9.0"]
                 [org.clojure/tools.logging      "0.4.1"]
                 [com.stuartsierra/component     "0.3.2"]
                 [funcool/catacumba              "2.2.1" :exclusions [com.google.guava ]]
                 [ch.qos.logback/logback-classic "1.2.3"]]

  :main ^:skip-aot clojure-web-server.core
  :target-path "target/%s"
  :uberjar-name "clojure-web-server-standalone.jar"
  :source-paths ["src/clj"]
  :test-paths ["test/clj" "src/clj"]

  :profiles {:uberjar {:aot          :all
                       :dependencies [[org.clojure/tools.nrepl "0.2.13"]]}
             :dev     {:dependencies [[org.clojure/tools.namespace "0.3.0-alpha4"]]
                       :source-paths ["dev"]}}

  :repl-options {:init-ns          user
                 :init             (set! *print-length* 100)}

  :clean-targets ^{:protect false} [:target-path])
