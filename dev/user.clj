(ns user
  (:require
   [com.stuartsierra.component.repl :refer [reset start stop system set-init]]
   [clojure-web-server.system :refer [new-system]]))

(def config
  {:web-server {:port 8088 }
   :mongo      {:uri "mongodb://127.0.0.1:27017/clojure-web-server-dev"}})

(defn dev-system
  "Constructs a system map suitable for interactive development."
  []
  (new-system config))

(set-init (fn [_] (dev-system)))
