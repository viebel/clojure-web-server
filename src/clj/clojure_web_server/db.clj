(ns clojure-web-server.db
  (:require
    [com.stuartsierra.component :as component]
    [monger.core :as mg]
    [monger.collection :as mc]
    [clojure.tools.logging :as log]
    [monger.collection :as mc]
    [cheshire.generate :refer [add-encoder encode-str]]
    [cheshire.core :as json]))

(defmacro dbg[x]
  (when *assert*
    `(let [x# ~x]
       (println (str '~x ": " x#))
       x#)))
;; ===========================================================================
;; utils


(add-encoder org.bson.types.ObjectId encode-str)

;; ===========================================================================
;; API


(defn users
  [{:keys [db]}]
  (mc/find-maps db "users" {}))

(defn add-user! [{:keys [db]} name password]
  (mc/insert db "users" {:name name :password password}))

(defn count-users [{:keys [db]}]
  (mc/count db "users"))

;; ===========================================================================
;; component
(comment
  (def mongo (mg/connect-via-uri "mongodb://localhost:27017/clojure-web-server"))
  (json/encode (users mongo))
  (mc/insert (:db mongo) "foo" {:bb "aaa"})
  (mc/find-maps (:db mongo) "foo" {})
  (mc/find mongo "foo")
  )


(defrecord DB [uri]

  component/Lifecycle

  (start [component]
    (try
      (let [{:keys [conn db]} (mg/connect-via-uri uri)]
        (assoc component
          :conn conn
          :db db))
      (catch Exception e
        (println "cannot connect to mongodb url:" uri "error: " (str e)))))

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
  (def db (-> (new-db {:uri "mongodb://localhost:27017/clojure-web-server"})
              component/start))
  (users db)
  (add-user! db-started "Samantha Lopez")
  (users db-started)

  (def rich-db (component/start (DB. "mongodb://127.0.0.1:27017/clojure-web-server")))
  (component/stop rich-db)
  )