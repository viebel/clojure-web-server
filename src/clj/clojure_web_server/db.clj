(ns clojure-web-server.db
  (:require
   [com.stuartsierra.component :as component]
   [clojure.tools.logging      :as log]))

;; ===========================================================================
;; utils



;; ===========================================================================
;; API




(defn users
  [db]
  [:itzik :eyal])


;; ===========================================================================
;; component

(defrecord DB [cfg db-name]

  component/Lifecycle

  (start [component]
    (log/infof ";; starting DB [%s]" db-name)
    (assoc component
      :client "client"
      :conn "connection"))

  (stop [component]
    (log/infof ";; stopping DB [%s]" db-name)
    ;; XXX: is there a way to release resources for datomic client API?
    (dissoc component :conn :client)))

;; ===========================================================================
;; constructor

(defn new-db [config]
  (map->DB (select-keys config [:cfg :db-name])))
