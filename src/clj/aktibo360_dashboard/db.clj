(ns aktibo360-dashboard.db
  (:require
   [com.stuartsierra.component :as component]
   [clojure.tools.logging      :as log]
   [clojure.set                :refer [union intersection]]
   [datomic.client.api         :as d])
  (:import
   [java.util Date]
   [java.time Instant ZoneId ZoneOffset LocalDateTime]
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

(defn parse-composite-index-value [value]
  (when-let [[_ user-id year month day] (re-find #"^([^\|]+)\|(\d{4})(\d{2})(\d{2})$" value)]
    [user-id (str year "-" month "-" day)]))

(defn unfuck-attribute [obj attr]
  ;; due to pull API implementation, ref attributes appear as objects and not as idents
  (if (contains? obj attr)
    (update obj attr (fn [val]
                       (if (map? val)
                         (:db/ident val)
                         (set (map :db/ident val)))))
    obj))

(defn unfuck-event-object [event]
  (-> event
      (unfuck-attribute :event/type)
      (unfuck-attribute :event.activity/type)))

;;; copied from medley.core/distinct-by
(defn distinct-by
  "Returns a lazy sequence of the elements of coll, removing any elements that return duplicate values when passed to a function f."
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result x]
          (let [fx (f x)]
            (if (contains? @seen fx)
              result
              (do (vswap! seen conj fx)
                  (rf result x)))))))))
  ([f coll]
   (let [step (fn step [xs seen]
                (lazy-seq
                 ((fn [[x :as xs] seen]
                    (when-let [s (seq xs)]
                      (let [fx (f x)]
                        (if (contains? seen fx)
                          (recur (rest s) seen)
                          (cons x (step (rest s) (conj seen fx)))))))
                  xs seen)))]
     (step coll #{}))))

(defn duration-seconds [start end]
  (Math/round (double (/ (- (.getTime end) (.getTime start)) 1000))))

(defn synthetic-activity [start end activity-types]
  {:event/timestamp start
   :event.activity/start-date start
   :event.activity/end-date end
   :event.activity/type activity-types
   :event.activity/confidence 100})

(defn synthetic-activity? [activity]
  (contains? (set (:event.activity/type activity)) :activity.type/stationary*))

(defn normalize-activities
  "merges adjacent activities of the same type, fixes the start and end dates to make it continuous."
  [activities]
  (loop [output [] input activities]
    (if (empty? input)
      (map (fn [{:keys [:event.activity/start-date :event.activity/end-date nested] :as activity}]
             (assoc activity
                    :duration (duration-seconds start-date end-date)
                    :avg-confidence (if (seq nested)
                                      (/ (reduce + (map :event.activity/confidence nested))
                                         (count nested))
                                      (:event.activity/confidence activity))))
           output)
      (let [[head & tail] input
            last-activity (last output)]
        (if (and last-activity
                 (not (synthetic-activity? last-activity))
                 (not (synthetic-activity? head))
                 (or (= (:event.activity/type last-activity)
                        (:event.activity/type head))
                     (nil? (:event.activity/type head))))
          (if (<= (duration-seconds (:event.activity/end-date last-activity)
                                    (:event.activity/start-date head))
                  300)
            (recur (conj (vec (butlast output))
                         (-> last-activity
                             (assoc :event.activity/end-date (:event.activity/end-date head))
                             (update :nested (fn [nested]
                                               (if (seq nested)
                                                 (conj nested head)
                                                 [last-activity head])))))
                   tail)
            (recur (vec (concat output [(synthetic-activity (:event.activity/end-date last-activity)
                                                            (:event.activity/start-date head)
                                                            #{:activity.type/stationary*})
                                        head]))
                   tail))
          (recur (conj
                  (if last-activity
                    (conj (vec (butlast output))
                          (assoc last-activity :event.activity/end-date (:event.activity/start-date head)))
                    output)
                  head)
                 tail))))))

(defn active-activity? [activity-type]
  ;; activity-type is a set
  (boolean
   (some #(contains? activity-type %)
                 [:activity.type/walking :activity.type/cycling :activity.type/automotive])))

(defn collapse-short-activities
  "collapses (merges into previous) activities shorter than min-duration seconds."
  [min-duration activities]
  (loop [output [] input activities]
    (if (empty? input)
      (map (fn [{:keys [nested] :as activity}]
             (assoc activity :avg-confidence (if (seq nested)
                                               (/ (reduce + (map :event.activity/confidence nested))
                                                  (count nested))
                                               (:event.activity/confidence activity))))
           output)
      (let [[head & tail] input
            last-activity (last output)]
        (if (and last-activity
                 (not (synthetic-activity? last-activity))
                 (not (synthetic-activity? head))
                 (>= min-duration (:duration head))
                 (or (seq (intersection (:event.activity/type last-activity)
                                        (:event.activity/type head)))
                     (not (active-activity? (:event.activity/type head)))))
          (recur (conj (vec (butlast output))
                       (-> last-activity
                           (assoc :event.activity/end-date (:event.activity/end-date head)
                                  :event.activity/type (union (:event.activity/type last-activity)
                                                              (:event.activity/type head))
                                  :duration (duration-seconds (:event.activity/start-date last-activity)
                                                              (:event.activity/end-date head)))
                           (update :nested concat (:nested head))))
                 tail)
          (recur (conj output head) tail))))))

(defn locations-between [locations start end]
  (filter (fn [{:keys [:event/timestamp]}]
            (and (>= (.getTime timestamp) (.getTime start))
                 (<= (.getTime timestamp) (.getTime end))))
          locations))

(defn localize-ts [ts tz-offset]
  (str (.atOffset (.toInstant ts) (ZoneOffset/ofTotalSeconds (* tz-offset 60)))))

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

(defn users
  [db]
  (d/q '[:find ?user-id ?email
         :in $
         :where
         [?user-id :user/email ?email]]
       db))

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

(defn user-event-objects
  [db user-id start end]
  (let [start* (.toInstant start)
        end*   (.plus (.toInstant end) 1 ChronoUnit/HOURS)
        dates  (take-while #(.isBefore % end*)
                           (periodic-seq start* 1 ChronoUnit/DAYS))]
    (log/debug :user-event-objects start* end*)
    (let [res (->> (d/q '[:find (pull ?e [* {:event/type [:db/ident]} {:event.activity/type [:db/ident]}])
                          :in $ [?composite-index ...] ?start ?end
                          :where
                          [?e :event/composite-index ?composite-index]
                          [?e :event/timestamp ?timestamp]
                          [(>= ?timestamp ?start)]
                          [(<= ?timestamp ?end)]]
                        db (map (partial composite-index-value user-id) dates) start end)
                   (map (comp unfuck-event-object first))
                   (distinct-by :event/record-id))]
      res)))

(defn user-application-event-objects
  [db user-id application-id start end]
  (let [start* (.toInstant start)
        end*   (.plus (.toInstant end) 1 ChronoUnit/HOURS)
        dates  (take-while #(.isBefore % end*)
                           (periodic-seq start* 1 ChronoUnit/DAYS))]
    (log/debug :user-application-event-objects dates)
    (let [res (->> (d/q '[:find (pull ?e [* {:event/type [:db/ident]} {:event.activity/type [:db/ident]}])
                          :in $ [?composite-index ...] ?application-id ?start ?end
                          :where
                          [?e :event/composite-index ?composite-index]
                          [?e :event/timestamp ?timestamp]
                          [(>= ?timestamp ?start)]
                          [(<= ?timestamp ?end)]
                          [?report :report/events ?e]
                          [?report :report/source ?report-source]
                          [?report-source :report.source/application-id ?application-id]]
                        db (map (partial composite-index-value user-id) dates) application-id start end)
                   (map (comp unfuck-event-object first))
                   (distinct-by :event/record-id))]
      res)))

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

(defn last-event-timestamp-before [db date-attr composite-index-values event-type application-id ts]
  (ffirst
   (d/q '[:find (max ?date)
          :in $ ?date-attr [?composite-index ...] ?event-type ?application-id ?max-timestamp
          :where
          [?e :event/composite-index ?composite-index]
          [?e :event/type ?event-type]
          [?e ?date-attr ?date]
          [(< ?date ?max-timestamp)]
          [?report :report/events ?e]
          [?report :report/source ?report-source]
          [?report-source :report.source/application-id ?application-id]]
        db
        date-attr
        composite-index-values
        event-type
        application-id
        ts)))

(defn last-location-before
  [db user-id application-id ts]
  (let [this-day               (.toInstant ts)
        the-day-before         (.minus this-day 1 ChronoUnit/DAYS)
        composite-index-values [(composite-index-value user-id this-day)
                                (composite-index-value user-id the-day-before)]
        last-location-ts       (last-event-timestamp-before db
                                                            :event/timestamp
                                                            composite-index-values
                                                            :event.type/location
                                                            application-id
                                                            ts)]
    (->> (d/q '[:find (pull ?e [* {:event/type [:db/ident]} {:event.activity/type [:db/ident]}])
                :in $ [?composite-index ...] ?application-id ?timestamp
                :where
                [?e :event/composite-index ?composite-index]
                [?e :event/type :event.type/location]
                [?e :event/timestamp ?timestamp]
                [?report :report/events ?e]
                [?report :report/source ?report-source]
                [?report-source :report.source/application-id ?application-id]]
              db
              composite-index-values
              application-id
              last-location-ts)
         (map first)
         (sort-by :event/timestamp)
         reverse
         (take 1)
         (map unfuck-event-object))))

(defn last-activity-before
  [db user-id application-id ts]
  (let [this-day               (.toInstant ts)
        the-day-before         (.minus this-day 1 ChronoUnit/DAYS)
        composite-index-values [(composite-index-value user-id this-day)
                                (composite-index-value user-id the-day-before)]
        last-activity-ts       (last-event-timestamp-before db
                                                            :event.activity/start-date
                                                            composite-index-values
                                                            :event.type/activity
                                                            application-id
                                                            ts)]
    (->> (d/q '[:find (pull ?e [* {:event/type [:db/ident]} {:event.activity/type [:db/ident]}])
                :in $ [?composite-index ...] ?application-id ?start-date
                :where
                [?e :event/composite-index ?composite-index]
                [?e :event.activity/start-date ?start-date]
                [?report :report/events ?e]
                [?report :report/source ?report-source]
                [?report-source :report.source/application-id ?application-id]]
              db
              composite-index-values
              application-id
              last-activity-ts)
         (map first)
         (sort-by :event/timestamp)
         reverse
         (take 1)
         (map unfuck-event-object))))

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

(defn user-tz [db user-id application-id start end]
  (let [tzs (user-timezone-offsets-in-range db user-id application-id start end)]
    (assert (= 1 (count tzs)) (format "The events between [%s] and [%s] span multiple timezones %s"
                                      (str start) (str end) (str tzs)))
    (first tzs)))

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
  (map (fn [composite-value]
         (let [[_ date] (parse-composite-index-value composite-value)]
           date))
       (ffirst
        (d/q '[:find (distinct ?composite-index)
               :in $ ?user-id ?application-id
               :where
               [?report-source :report.source/user ?user-id]
               [?report-source :report.source/application-id ?application-id]
               [?report :report/source ?report-source]
               [?report :report/events ?event]
               [?event :event/composite-index ?composite-index]]
             db user-id application-id))))

(defn compat-re-calculate-event-dates
  [db user-id]
  (map (fn [[application-id composite-values]]
         [application-id (map (fn [composite-value]
                                (let [[_ date] (parse-composite-index-value composite-value)]
                                  date))
                              composite-values)])
       (d/q '[:find ?application-id (distinct ?composite-index)
              :in $ ?user-id
              :where
              [?report-source :report.source/user ?user-id]
              [?report-source :report.source/application-id ?application-id]
              [?report :report/source ?report-source]
              [?report :report/events ?event]
              [?event :event/composite-index ?composite-index]]
            db user-id)))

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

(defn date->range [date]
  (let [start (.truncatedTo (LocalDateTime/ofInstant (.toInstant date) (ZoneId/of "UTC")) ChronoUnit/DAYS)
        end   (-> start (.withHour 23) (.withMinute 59) (.withSecond 59) (.withNano 999999999))]
    [(Date/from (.toInstant start ZoneOffset/UTC)) (Date/from (.toInstant end ZoneOffset/UTC))]))

(defn adjust-tz [date offset]
  (if (zero? offset)
    date
    (-> date
        .toInstant
        (LocalDateTime/ofInstant (ZoneId/of "UTC"))
        (.toInstant (ZoneOffset/ofTotalSeconds (* offset 60)))
        Date/from)))

(defn timeline
  [db user-id application-id date adjust-tz?]
  (let [[start end]   (date->range date)
        tz-offset     (if adjust-tz?
                        (user-tz db user-id application-id start end)
                        0)
        start         (adjust-tz start tz-offset)
        end           (adjust-tz end tz-offset)
        events        (user-application-event-objects db user-id application-id start end)
        last-activity (last-activity-before db user-id application-id start)
        last-location (last-location-before db user-id application-id (:event.activity/end-date (first last-activity)))
        activities    (->> events
                           (filter #(= :event.type/activity (:event/type %)))
                           (concat last-activity)
                           (sort-by :event.activity/start-date))
        locations (->> events
                       (filter #(= :event.type/location (:event/type %)))
                       (concat last-location)
                       (sort-by :event/timestamp))
        visits       (->> events
                          (filter #(= :event.type/visit (:event/type %)))
                          (sort-by :event.visit/arrival-date))]
    {:start         start
     :end           end
     :tz-offset     tz-offset
     :raw-events    events
     :last-activity last-activity
     :last-location last-location
     :activities    (->> activities
                         normalize-activities
                         (collapse-short-activities 300)
                         (map (fn [{:keys [:event.activity/start-date :event.activity/end-date] :as activity}]
                                  (assoc activity
                                         :locations (map (fn [location]
                                                           (assoc location
                                                                  :timestamp-local (localize-ts (:event/timestamp location) tz-offset)))
                                                         (let [locations (locations-between locations start-date end-date)]
                                                           (if (seq locations)
                                                             locations
                                                             (last-location-before db user-id application-id end-date))))
                                         :start-date-local (localize-ts start-date tz-offset)
                                         :end-date-local (localize-ts end-date tz-offset)
                                         :nested (map (fn [activity]
                                                        (assoc activity
                                                               :duration (duration-seconds (:event.activity/start-date activity)
                                                                                           (:event.activity/end-date activity))
                                                               :start-date-local (localize-ts (:event.activity/start-date activity) tz-offset)
                                                               :end-date-local (localize-ts (:event.activity/end-date activity) tz-offset)))
                                                      (:nested activity))
                                         :visit (filter (fn [visit]
                                                          (and (>= (.getTime (:event.visit/arrival-date visit))
                                                                   (.getTime (:event.activity/start-date activity))
                                                                   (.getTime (:event.visit/departure-date visit)))))
                                                        visits)))))}))

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
