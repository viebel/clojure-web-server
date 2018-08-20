(ns user
  (:require
    [clojure.repl                 :refer [doc source]]
    [clojure.pprint               :refer [pprint]]
    [clojure.tools.namespace.repl :refer [refresh]]
    [com.stuartsierra.component   :as component]
    [clojure.tools.logging        :as log]
    [clojure-web-server.system   :refer [new-system]]))

;; ===========================================================================
;; REPL workflow

(def dev-config
  {:web-server {:port 8086 }
   #_:db         #_{:cfg     {:server-type :peer-server
                              :access-key  "foo"
                              :secret      "bar"
                              :endpoint    "localhost:9999"}
                    :db-name "clojure-web-server"}})

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



