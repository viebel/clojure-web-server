(ns clojure-web-server.core
  (:gen-class)
  (:require
   [com.stuartsierra.component   :as component]
   [clojure-web-server.system   :refer [new-system]]))

(def config
  {:web-server {:port 8087 }
   :mongo      {:uri "mongodb://127.0.0.1:27017/clojure-web-server"}})

(defn -main
  "I don't do a whole lot ... yet."
  [& _]
  (println "Hello, World!")
  (let [system (new-system config)]
    (component/start system)))

(comment
  (keys com.stuartsierra.component.repl/system))
