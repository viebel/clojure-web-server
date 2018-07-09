(ns aktibo360-dashboard.db
  (:require
   [com.stuartsierra.component :as component]
   [clojure.tools.logging      :as log]
   [datomic.client.api         :as d])
  (:import
   [java.util Date]
   [java.time Instant ZoneId]
   [java.time.temporal ChronoUnit]
   [java.time.format DateTimeFormatter]))

;; ===========================================================================
;; utils

(def utc-date-formatter (.withZone (DateTimeFormatter/ofPattern "uuuuMMdd") (ZoneId/of "UTC")))
(def utc-date-hour-formatter (.withZone (DateTimeFormatter/ofPattern "uuuuMMdd'T'HH") (ZoneId/of "UTC")))

(defn composite-index-value [user-id ts]
  (str user-id "|"
       (.format utc-date-formatter (if (instance? Date ts) (.toInstant ts) ts))))

(defn periodic-seq
  "Returns lazy sequence of dates starting from `initial` incremented by `n` `unit`s."
  [^Instant initial n ^ChronoUnit unit]
  (iterate #(.plus % n unit) initial))

;; ===========================================================================
;; API

(defn curr-db
  "Returns current db value. Use the same db value for all functions for consistent results."
  [{:keys [conn]}]
  (d/db conn))

(defn entity
  "Returns all attributes of the given entity"
  [db id]
  (d/pull db '[*] id))

(defn user-id
  "Returns user id by email."
  [db email]
  (ffirst
   (d/q '[:find ?user-id
          :in $ ?email
          :where [?user-id :user/email ?email]]
        db email)))

(defn user-events
  "Returns list of event ids for given user and date."
  [db user-id date]
  (map first
       (d/q '[:find ?e
              :in $ ?composite-index
              :where [?e :event/composite-index ?composite-index]]
            db (composite-index-value user-id date))))

(defn events-hours-frequencies
  "returns a list of tuples ('YYYY-MM-DDTHH', event count)"
  [db event-ids]
  (->> (d/q '[:find ?timestamp
              :in $ [?event-id ...]
              :where [?event-id :event/timestamp ?timestamp]]
            db event-ids)
       (map (fn [[ts]]
              (.format utc-date-hour-formatter (.toInstant ts))))
       frequencies
       (sort-by first)))

(defn events-hours-type-frequencies
  "returns a list of tuples ('YYYY-MM-DDTHH', {:type count, :type count})"
  [db event-ids]
  (->> (d/q '[:find ?type ?timestamp
              :in $ [?event-id ...]
              :where
              [?event-id :event/type ?type-id]
              [?event-id :event/timestamp ?timestamp]
              [?type-id :db/ident ?type]]
            db event-ids)
       (map (fn [[type ts]]
              [(.format utc-date-hour-formatter (.toInstant ts)) type]))
       (group-by first)
       (map (fn [[hour types]]
              [hour (frequencies (map (comp keyword name last) types))]))
       (sort-by first)))

(defn last-known-location
  "returns last known location event id before the given timestamp"
  [db user-id ts]
  (let [this-day       (.toInstant ts)
        the-day-before (.minus this-day 1 ChronoUnit/DAYS)]
    (some->> (d/q '[:find ?e
                    :in $ [?composite-index ...] ?max-timestamp
                    :where
                    [?e :event/composite-index ?composite-index]
                    [?e :event/type :event.type/location]
                    [?e :event/timestamp ?timestamp]
                    [(< ?timestamp ?max-timestamp)]]
                  db
                  [(composite-index-value user-id this-day)
                   (composite-index-value user-id the-day-before)]
                  ts)
             (sort-by last)
             last
             first)))

(defn user-timezone-offsets-in-range
  "returns a list of timezone offsets in minutes for given user and timerange in UTC"
  [db user-id application-id start end]
  (let [start     (.toInstant start)
        end       (.toInstant end)
        min-start (.minus start 12 ChronoUnit/HOURS)
        max-end   (.plus end 14 ChronoUnit/HOURS)
        dates     (take-while #(.isBefore % max-end)
                              (periodic-seq min-start 1 ChronoUnit/DAYS))]
    (map first
         (d/q '[:find ?timezone
                :in $ [?composite-index ...] ?application-id ?start ?end
                :where
                [?e :event/composite-index ?composite-index]
                [?e :event/timestamp ?timestamp]
                [(>= ?timestamp ?start)]
                [(<= ?timestamp ?end)]
                [?report :report/events ?e]
                [?report :report/source ?report-source]
                [?report-source :report.source/application-id ?application-id]
                [?report :report/timezone ?timezone]]
              db
              (map (partial composite-index-value user-id) dates)
              application-id
              (Date/from min-start)
              (Date/from max-end)))))

(defn user-application-reports
  "returns a list of report ids from given application by given user in given time range
   as tuples (timestamp, report id)"
  [db user-id application-id start end]
  (->> (d/q '[:find ?timestamp ?report
              :in $ ?user-id ?application-id ?start ?end
              :where
              [?report-source :report.source/user ?user-id]
              [?report-source :report.source/application-id ?application-id]
              [?report :report/source ?report-source]
              [?report :report/timestamp ?timestamp]
              [(>= ?timestamp ?start)]
              [(<= ?timestamp ?end)]]
            db user-id application-id start end)
       (sort-by first)))

(defn report-devices
  [db report-ids]
  (map first
       (d/q '[:find ?device
              :in $ [?report ...]
              :where
              [?report :report/source ?report-source]
              [?report-source :report.source/device ?device]]
            db report-ids)))

(defn re-calculate-event-dates
  [db user-id application-id]
  (ffirst
   (d/q '[:find (distinct ?composite-index)
          :in $ ?user-id ?application-id
          :where
          [?report-source :report.source/user ?user-id]
          [?report-source :report.source/application-id ?application-id]
          [?report :report/source ?report-source]
          [?report :report/events ?event]
          [?event :event/composite-index ?composite-index]]
        db user-id application-id)))

(defn all-user-application-ids
  [db user-id]
  (log/debug :all-user-application-ids db user-id)
  (ffirst
   (d/q '[:find (distinct ?application-id)
          :in $ ?user-id
          :where
          [?report-source :report.source/user ?user-id]
          [?report-source :report.source/application-id ?application-id]]
        db user-id)))

(defn all-user-applications-with-versions
  [db user-id]
  (d/q '[:find ?application-id (distinct ?application-version)
         :in $ ?user-id
         :where
         [?report-source :report.source/user ?user-id]
         [?report-source :report.source/application-id ?application-id]
         [?report-source :report.source/app-version ?application-version]]
       db user-id))

;; ===========================================================================
;; component

(defrecord DB [cfg db-name]

  component/Lifecycle

  (start [component]
    (log/infof ";; starting DB [%s]" db-name)
    (let [client (d/client cfg)]
      (assoc component
             :client client
             :conn   (d/connect client {:db-name db-name}))))

  (stop [component]
    (log/infof ";; stopping DB [%s]" db-name)
    ;; XXX: is there a way to release resources for datomic client API?
    (dissoc component :conn :client)))

;; ===========================================================================
;; constructor

(defn new-db [config]
  (map->DB (select-keys config [:cfg :db-name])))
