(defproject aktibo360-dashboard "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure            "1.9.0"]
                 [org.clojure/tools.logging      "0.4.1"]
                 [org.clojure/tools.reader       "1.3.0"]
                 [org.clojure/core.async         "0.4.474"]
                 [com.stuartsierra/component     "0.3.2"]
                 [funcool/catacumba              "2.2.1"]
                 [com.datomic/client-pro         "0.8.17"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 ;; client
                 [org.clojure/clojurescript      "1.10.339"]
                 [reagent                        "0.8.1"]
                 [re-frame                       "0.10.5"]
                 [day8.re-frame/http-fx          "0.1.6"]]

  :plugins [[lein-cljsbuild "1.1.7"]]

  :main ^:skip-aot aktibo360-dashboard.core
  :target-path "target/%s"
  :uberjar-name "aktibo360-dashboard-standalone.jar"
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]

  :profiles {:uberjar {:aot          :all
                       :dependencies [[org.clojure/tools.nrepl "0.2.13"]]}
             :dev     {:dependencies [[org.clojure/tools.namespace "0.3.0-alpha4"]
                                      [binaryage/devtools          "0.9.10"]
                                      [figwheel-sidecar            "0.5.16"]
                                      [com.cemerick/piggieback     "0.2.2"]]
                       :source-paths ["dev"]
                       :plugins      [[lein-figwheel "0.5.16"]]}}

  :repl-options {:init-ns          user
                 :init             (set! *print-length* 100)
                 :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    :target-path]

  :figwheel {:css-dirs    ["resources/public/css"]
             :server-port 8086}

  :cljsbuild {:builds
              [{:id           "dev"
                :source-paths ["src/cljs"]
                :figwheel     {:on-jsload "aktibo360-dashboard.core/mount-root"}
                :compiler     {:main                 aktibo360-dashboard.core
                               :output-to            "resources/public/js/compiled/app.js"
                               :output-dir           "resources/public/js/compiled/out"
                               :asset-path           "js/compiled/out"
                               :source-map-timestamp true
                               :preloads             [devtools.preload]}}
               {:id           "min"
                :source-paths ["src/cljs"]
                :compiler     {:main            aktibo360-dashboard.core
                               :output-to       "resources/public/js/compiled/app.min.js"
                               :output-dir      "resources/public/js/compiled/out-min"
                               :source-map      "resources/public/js/compiled/app.min.js.map"
                               :optimizations   :advanced
                               :closure-defines {goog.DEBUG false}
                               :pretty-print    false}}]})
