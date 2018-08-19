(ns clojure-web-server.system
  (:require
   [com.stuartsierra.component     :as component]
   [clojure-web-server.web-server :refer [new-web-server]]
   [clojure-web-server.db         :refer [new-db]]))

(defn new-system [config]
  (component/system-map
    :web-server (new-web-server (:web-server config))
    ;:db         (new-db         (:db         config))
    ))
