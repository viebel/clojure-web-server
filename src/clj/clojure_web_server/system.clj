(ns clojure-web-server.system
  (:require
   [com.stuartsierra.component :as component]
   [clojure-web-server.web-server :refer [new-web-server]]
   [clojure-web-server.db         :refer [new-db]]))


(def config
  {:web-server {:port 8876}
   :mongo      {:uri "mongodb://localhost:27017/foo"}})

(defn new-system [config]
  (component/system-map
    :web-server (new-web-server (:web-server config))
    :mongo-db (new-db (:mongo config))))


(comment
  (def system (component/start (new-system config)))

  (component/stop system)

  )
