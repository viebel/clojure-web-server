(ns clojure-web-server.core
  (:gen-class)
  (:require
    [com.stuartsierra.component   :as component]
    [clojure.tools.logging        :as log]
    [clojure-web-server.system   :refer [new-system]]))



(def config
  {:web-server {:port 8087 }})

(defn -main
  "I don't do a whole lot ... yet."
  [& _]
  (println "Hello, World!")
  (let [system (new-system config)]
    (component/start system)))

