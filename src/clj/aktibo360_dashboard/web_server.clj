(ns aktibo360-dashboard.web-server
  (:require
   [com.stuartsierra.component :as component]
   [clojure.tools.logging      :as log]
   [catacumba.core             :as ct]
   [catacumba.http             :as http]
   [catacumba.handlers.parse   :as parse]
   [catacumba.handlers.misc    :as misc]
   [catacumba.handlers.auth    :as cauth]
   [catacumba.serializers      :as ser]
   [buddy.sign.jwt             :as jwt]
   [clojure.core.async         :as async :refer [go-loop <! >!]]
   [clojure.string             :as string]
   [cognitect.transit          :as transit]
   [aktibo360-dashboard.db     :as db])
  (:import
    [java.util UUID Date]
    [java.io ByteArrayOutputStream]
    [java.time Instant LocalDate ZoneId]
    [java.time.format DateTimeFormatter]))


;; ===========================================================================
;; utils

(defn ->transit [data]
  (with-open [out (ByteArrayOutputStream.)]
    (try
      (let [w (transit/writer out :json)]
        (transit/write w data)
        (.toString out))
      (catch Exception ex
        (log/error ex "failed to convert data to transit" data)))))

(defn logged-in? [identity]
  (contains? identity :user))

(defn admin? [identity]
  (boolean (get-in identity [:user :admin])))

(defn compat-user-report-dates [db user-id]
  (let [curr-db      (db/curr-db db)
        applications (db/all-user-application-ids curr-db user-id)]
    (sort-by first
             (mapcat (fn [application-id]
                       (map (fn [date]
                              [date application-id])
                            (db/re-calculate-event-dates curr-db user-id application-id)))
                     applications))))

(defn compat-user-report-dates2 [db user-id]
  (let [curr-db (db/curr-db db)]
    (sort-by first
             (mapcat (fn [[application-id dates]]
                       (map (fn [date]
                              [date application-id])
                            dates))
                     (db/compat-re-calculate-event-dates curr-db user-id)))))

(defn compat-user-events [db user-id start end application-id]
  (let [curr-db (db/curr-db db)]
    (db/user-event-objects curr-db user-id start end)))

(defn compat-users [db]
  (let [curr-db (db/curr-db db)]
    (map (fn [[id email]]
           {:id    id
            :email email})
         (db/users curr-db))))

(def utc-basic-date-formatter (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

(defn parse-date [s]
  (-> s LocalDate/parse (.atStartOfDay (ZoneId/of "UTC")) .toInstant Date/from))

(defn compat-timeline [db user-id application-id date adjust-tz?]
  (let [curr-db (db/curr-db db)]
    (db/timeline curr-db user-id application-id date adjust-tz?)))

;; ===========================================================================
;; handlers

(defn logging-handler [context outcome]
  (when-not (= "/status" (:path context))
    (log/debug {:context context :outcome outcome})))

(defn add-state [& {:as state}]
  (fn [_]
    (ct/delegate state)))

(defn status-handler [_]
  (http/ok "ok"))

#_(defn login-handler [{:keys [::secret ::users data] :as context}]
  (log/debug :login-handler data)
  (if-let [user (users/authenticate users (assoc data :client-ip (client-ip context)))]
    (http/ok (encode-user user secret)
             {:content-type "application/json"})
    (http/unauthorized "not today, buddy...")))

#_(defn commands-handler
  {:handler-type :catacumba/websocket}
  [{:keys [in out query-params ::secret ::db-listener] :as context}]
  (log/debug :commands-handler query-params)
  ;; we don't need to do anything special to validate the access-token
  ;; if it's not valid, an exception will be thrown and the websocket will close
  (let [identity (jwt/unsign (:access-token query-params) secret)
        id       (UUID/randomUUID)
        ch       (async/chan)]
    (log/debug :commands-handler :identity identity)
    (log/debug :commands-handler "adding observer" id)
    (dbl/add-observer db-listener id {:user-id (get-in identity [:user :id])
                                      :ch      ch})
    (go-loop []
      (log/debug :commands-handler "inside loop")
      (let [[v c] (async/alts! [in ch (async/timeout 30000)])]
        (cond

          (and (nil? v) (= c in))
          (do
            (log/debug "channel closed?")
            (dbl/remove-observer db-listener id))

          (= c in)
          (do
            (log/debug "received input on channel" v)
            (>! out v)
            (recur))

          (= c ch)
          (do
            (log/debug "received db change")
            (try
              (>! out (->transit v))
              (catch Exception ex
                (log/error "failed to convert db change to transit for" v)))
            (recur))

          :else
          (do
            (>! out (->transit {:type :ping}))
            (recur)))))))

#_(defn admin-users-handler [{:keys [::users identity]}]
  (if (admin? identity)
    (http/ok (ser/encode (users/users users) :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "you're not an admin")))

#_(defn admin-user-events-handler [{:keys [::reporter identity data]}]
  (log/debug :admin-user-events-handler data)
  (if (admin? identity)
    (http/ok (ser/encode
              (reporter/user-events2 reporter
                                     (:user-id data)
                                     (:start data)
                                     (:end data)
                                     (:application-id data))
              :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "you're not an admin")))

#_(defn admin-raw-user-events-handler [{:keys [::reporter identity data]}]
  (log/debug :admin-raw-user-events-handler data)
  (if (admin? identity)
    (http/ok (ser/encode
              (reporter/raw-user-events2 reporter
                                         (:user-id data)
                                         (:start data)
                                         (:end data))
              :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "No soup for you!")))

#_(defn events-handler [{:keys [::reporter identity data]}]
  (log/debug :events-handler data)
  (if (logged-in? identity)
    (http/ok (ser/encode
              (reporter/user-events reporter
                                    (get-in identity [:user :id])
                                    (:start data)
                                    (:end data)
                                    (:device-id data))
              :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

#_(defn admin-user-report-dates-handler [{:keys [::reporter identity data]}]
  (log/debug :admin-user-report-dates-handler data)
  (if (admin? identity)
    (http/ok (ser/encode
              (reporter/user-report-dates2 reporter
                                          (:user-id data))
              :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "you're not an admin")))

#_(defn report-dates-handler [{:keys [::reporter identity]}]
  (log/debug :report-dates-handler)
  (if (logged-in? identity)
    (http/ok (ser/encode
              (reporter/user-report-dates reporter
                                          (get-in identity [:user :id]))
              :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

#_(defn user-attributes-handler [{:keys [::user-attributes route-params identity]}]
  (if (logged-in? identity)
    (let [{id :id date :date} route-params
          survey (ua/get-survey user-attributes (read-string id) date)
          ;Here I want to get the data from the worker who saves the attributes
          {home :home work :work commute :commute} (ua/get-user-attributes user-attributes (read-string id) date)]

      (http/ok (ser/encode (ua/to-ui-object home work commute survey)
                           :json {:content-type "application/json"})))
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

#_(defn user-attributes-survey-handler [{:keys [::user-attributes identity route-params data]}]
  (if (logged-in? identity)
    (let [{id :id date :date} route-params]
      (http/ok (ser/encode (ua/update-user-survey user-attributes (read-string id) date data)
                           :json {:content-type "application/json"})))
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

;We want for kenko that login will work as register if user exist
#_(defn kenko-login-handler [{:keys [::secret ::users data] :as context}]
  (log/debug :kenko-login-handler)
  (if-let [user (users/join-kenko users (assoc data :client-ip (client-ip context)))]
    (http/ok (encode-user user secret)
             {:content-type "application/json"})
    (http/unauthorized "not today, buddy...")))

#_(defn upload-photo-handler [{:keys [::users identity data route-params]}]
  (if (logged-in? identity)
    (let [url (users/upload-picture users data (:version route-params))]
      (http/ok (ser/encode {:url (parse-profile-picture-url url)} :json) {:content-type "application/json"}))
    (http/unauthorized "not today, buddy...")))


#_(defn get-signed-url-handler [{:keys [::storage route-params identity]}]
  (if (logged-in? identity)
    (let [{id :id uuid :uuid} route-params
          url (storage/get-url storage (str id "/" uuid))]
      (http/ok (ser/encode {:url url} :json) {:content-type "application/json"}))
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

#_(defn app-list-handler [{:keys [identity]}]
  (if (logged-in? identity)
    (http/ok (ser/encode users/app-list :json) {:content-type "application/json"})
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

#_(defn user-app-list-handler [{:keys [::users data identity route-params]}]
  (if (logged-in? identity)
    (http/ok (ser/encode (users/set-user-app-list users
                                                  (:device route-params)
                                                  (:apps data))
                         :json)
             {:content-type "application/json"})
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

#_(defn show-timeline-handler [{:keys [identity route-params query-params]}]
  (if (logged-in? identity)
    (http/ok (ser/encode {:user           (get-in identity [:user :id])
                          :date           (:date route-params)
                          :adjust-tz?     (:adjust-tz? query-params)
                          :application-id (:application-id query-params)}
                         :json)
             {:content-type "application/json"})
    (http/unauthorized (ser/encode (select-keys identity [:cause]) :json)
                       {:content-type "application/json"})))

#_(defn admin-show-timeline-handler [{:keys [::timeline identity route-params query-params]}]
  (if (admin? identity)
    (http/ok (ser/encode (dissoc
                          (tl/timeline timeline
                                       (Long/parseLong (:user route-params) 10)
                                       (:application-id query-params)
                                       (tf/parse (tf/formatters :date) (:date route-params))
                                       (:adjust-tz? query-params))
                          :raw-events :raw-activities :raw-headings :raw-locations :raw-visits)
                         :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "You're not admin")))

(defn list-applications-handler [{:keys [identity ::db]}]
  (if (logged-in? identity)
    (let [curr-db (db/curr-db db)]
      (http/ok (ser/encode
                (db/all-user-application-ids curr-db (get-in identity [:user :id]))
                :transit+json)
               {:content-type "application/transit+json"}))
    (http/unauthorized "No soup for you!")))

(defn admin-list-applications-handler [{:keys [identity ::db route-params]}]
  (log/debug :admin-list-applications-handler identity route-params)
  (http/ok "ok"))

(defn list-event-dates-handler [_])

(defn admin-compat-user-report-dates-handler [{:keys [identity ::db data]}]
  (if (admin? identity)
    (http/ok (ser/encode
              (compat-user-report-dates db (:user-id data))
              :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "you're not an admin")))

(defn admin-compat-user-events-handler [{:keys [identity ::db data]}]
  (if (admin? identity)
    (http/ok (ser/encode
              (compat-user-events db (:user-id data) (:start data) (:end data) (:application-id data))
              :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "No soup for you!")))

(defn admin-compat-users-handler [{:keys [identity ::db]}]
  (if (admin? identity)
    (http/ok (ser/encode (compat-users db) :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "No soup for you!")))

(defn admin-compat-timeline-handler [{:keys [identity ::db route-params query-params]}]
  (if (admin? identity)
    (http/ok (ser/encode (compat-timeline db
                                          (Long/parseLong (:user route-params) 10)
                                          (:application-id query-params)
                                          (parse-date (:date route-params))
                                          true)
                         :transit+json)
             {:content-type "application/transit+json"})
    (http/unauthorized "No soup for you!")))

;; ===========================================================================
;; routes

(defn api-routes [jwt-secret]
  [:prefix "api"
   [:any (misc/cors
          {:origin            #{"https://a360.aktibo.io"
                                "http://dev.a360.aktibo.io:8082"}
           :max-age           22896000
           :allow-credentials true
           :allow-headers     [:content-type :authorization]})]
   [:any (cauth/auth
          (cauth/jws-backend
           {:secret jwt-secret
            :on-error (fn [context ex]
                        (log/error ex "failed to decode token")
                        (let [{:keys [cause]} (ex-data ex)]
                          {:failed? true :cause cause}))}))]
   [:any (parse/body-params)]
   [:get "1/applications" list-applications-handler]
   [:get "1/event-dates/:application" list-event-dates-handler]
   [:prefix "admin"
    [:get "1/applications/:user" admin-list-applications-handler]
    [:prefix "compat"
     [:get "1/users" admin-compat-users-handler]
     [:post "1/user-events" admin-compat-user-events-handler]
     [:post "1/user-report-dates" admin-compat-user-report-dates-handler]
     [:get "1/timeline/:user/:date" admin-compat-timeline-handler]]]])

#_(defn ws-routes []
  [:prefix "ws"
   [:prefix "1"
    [:any #'commands-handler]]])

;; ===========================================================================
;; component

(defrecord WebServer [port secret db]

  component/Lifecycle

  (start [component]
    (log/infof ";; starting WebServer on port [%d]" port)
    (let [routes [[:any (misc/log logging-handler)]
                  [:any (add-state ::secret secret
                                   ::db     db)]
                  [:get "status" status-handler]
                  (api-routes secret)]]
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
   (map->WebServer (select-keys config [:port :secret]))
   [:db]))
