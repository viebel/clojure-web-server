(ns aktibo360-dashboard.system
  (:require
   [com.stuartsierra.component     :as component]
   [aktibo360-dashboard.web-server :refer [new-web-server]]
   [aktibo360-dashboard.db         :refer [new-db]]))

(defn new-system [config]
  (component/system-map
   :web-server (new-web-server (:web-server config))
   :db         (new-db         (:db         config))))
