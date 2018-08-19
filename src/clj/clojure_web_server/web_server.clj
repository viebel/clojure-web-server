(ns clojure-web-server.web-server
  (:require
   [com.stuartsierra.component :as component]
   [clojure.tools.logging      :as log]
   [catacumba.core             :as ct]
   [catacumba.http             :as http]
   [catacumba.handlers.parse   :as parse]
   [catacumba.handlers.misc    :as misc]
   [clojure-web-server.db     :as db]))


;; ===========================================================================
;; utils


;; ===========================================================================
;; handlers

(defn hello-world [_]
  (http/ok "hello world"))

(defn logging-handler [context outcome]
  (when-not (= "/status" (:path context))
    (log/debug {:context context :outcome outcome})))

(defn status-handler [_]
  (http/ok "ok you"))

;; ===========================================================================
;; routes

(defn api-routes []
  [:any (misc/cors
          {:origin            #{"*"}
           :max-age           22896000
           :allow-credentials true
           :allow-headers     [:content-type :authorization]})]
  [:any (parse/body-params)]
  [:get "hello" hello-world])


;; ===========================================================================
;; component

(defrecord WebServer [port secret db]

  component/Lifecycle

  (start [component]
    (log/infof ";; starting WebServer on port [%d]" port)
    (let [routes [[:any (misc/log logging-handler)]
                  [:get "status" status-handler]
                  (api-routes)]]
      (assoc component :server (ct/run-server (ct/routes routes)
                                              {:port          port
                                               :host          "0.0.0.0"
                                               :debug         false
                                               :max-body-size 20971520}))))

  (stop [component]
    (log/info ";; stopping WebServer")
    (when-let [server (:server component)]
      (try
        (.stop server)
        (catch Throwable ex
          (log/error ex "failed to stop server"))))
    (dissoc component :server)))

;; ===========================================================================
;; constructor

(defn new-web-server [config]
  (component/using
   (map->WebServer (select-keys config [:port]))
   []))
