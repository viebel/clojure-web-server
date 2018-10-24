(ns user
  (:require
    [clojure.tools.namespace.repl :refer [refresh]]
    [com.stuartsierra.component   :as component]
    [clojure-web-server.system   :refer [new-system]]))

;; ===========================================================================
;; REPL workflow

(def dev-config
  {:web-server {:port 8086 }
   :mongo {:uri "mongodb://127.0.0.1:27017/clojure-web-server"}})

(def system nil)

(defn init []
  (alter-var-root
   #'system
   (constantly
    (new-system dev-config))))

(defn start []
  (alter-var-root
   #'system
   component/start))

(defn stop []
  (alter-var-root
   #'system
   (fn [s]
     (when s
       (component/stop s)))))

(defn go []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/go))

(comment
  (reset))

