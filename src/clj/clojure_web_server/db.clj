(ns clojure-web-server.db
  (:require
    [com.stuartsierra.component :as component]
    [monger.core :as mg]
    [monger.collection :as mc]
    [clojure.tools.logging :as log]
    [monger.collection :as mc]
    [cheshire.generate :refer [add-encoder encode-str]]
    [cheshire.core :as json]))

;; ===========================================================================
;; utils


(add-encoder org.bson.types.ObjectId encode-str)

;; ===========================================================================
;; API


(defn users
  [{:keys [db]}]
  (mc/find-maps db "users" {}))

(defn add-user! [{:keys [db]} name]
  (mc/insert db "users" {:name name}))

;; ===========================================================================
;; component
(comment
  (def mongo (mg/connect-via-uri "mongodb://localhost:27017/clojure-web-server"))
  (json/encode (users mongo))
  (mc/insert (:db mongo) "foo" {:bb "asadsdadsds"})
  (mc/find-maps (:db mongo) "foo" {})
  (mc/find mongo "foo")
  )

(defrecord DB [uri]

  component/Lifecycle

  (start [component]
    (log/infof ";; starting mongo DB [%s]" uri)
    (let [{:keys [conn db]} (mg/connect-via-uri uri)]
      (assoc component
        :conn conn
        :db db)))

  (stop [component]
    (log/infof ";; stopping DB [%s]" uri)
    (when-let [connection (:conn component)]
      (mg/disconnect connection))
    (dissoc component :conn :db)))

;; ===========================================================================
;; constructor

(defn new-db [config]
  (map->DB {:uri (:uri config)}))


(comment
  (def db (new-db {:uri "mongodb://localhost:27017/clojure-web-server"}))
  (component/start db)
  (users db)

  (def db-started (component/start db))
  (add-user! db-started "Samantha Lopez")
  (users db-started)

  (def db (:mongo-db com.stuartsierra.component.repl/system))
  (add-user! db "Samantha Lopez")
  (users db)
  )


