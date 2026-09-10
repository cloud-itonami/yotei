(ns yotei.availability-test
  "What a stranger is actually offered.

  The calendar under test is in JST (+09:00) throughout, because every
  interesting bug in this namespace is a timezone bug and a UTC calendar would
  hide all of them."
  (:require [clojure.test :refer [deftest is testing]]
            [yotei.availability :as av]
            [yotei.time :as t]))

(def CAL "did:web:app.itonami.cloud:yotei:calendar:alice")
(def JST 540)

(defn- at [s] (t/parse-instant s))

(defn- jst-cal [& windows]
  (av/calendar CAL {:yotei/tz-offset-min JST
                    :yotei/slot-min 30
                    :yotei/notice-min 60
                    :yotei/horizon-days 60
                    :yotei/windows (vec windows)}))

(defn- confirmed* [start dur]
  {"status" "confirmed" "calendarDid" CAL "startEpochMin" start "durationMin" dur})

(defn- starts [openings]
  (mapv #(t/format-instant (:yotei/start-epoch-min %)) openings))

;; ── windows expand on the owner's local weekday ──
(deftest monday-window-expands-to-utc-instants
  ;; 2026-03-09 is a Monday (pinned in time_test). 10:00-12:00 JST is
  ;; 01:00-03:00 UTC the same date.
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [] (at "2026-03-08T00:00:00Z"))]
    (is (= ["2026-03-09T01:00:00Z" "2026-03-09T01:30:00Z"
            "2026-03-09T02:00:00Z" "2026-03-09T02:30:00Z"]
           (starts os)))))

(deftest weekday-is-decided-in-local-time-not-utc
  ;; The bug this exists to catch: 08:00-09:00 JST on Monday is 23:00-24:00 UTC
  ;; on **Sunday**. Deciding the weekday in UTC would look at Sunday, find no
  ;; Monday window, and publish nothing — the owner's whole morning would
  ;; silently vanish for anyone west of them.
  (let [cal (jst-cal (av/window :monday "08:00" "09:00"))
        os (av/openings cal (at "2026-03-08T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [] (at "2026-03-07T00:00:00Z"))]
    (is (= ["2026-03-08T23:00:00Z" "2026-03-08T23:30:00Z"] (starts os)))
    (testing "and it is grouped under the owner's Monday, not UTC's Sunday"
      (is (= [{:yotei/date "2026-03-09" :yotei/openings os}]
             (av/by-local-day cal os))))))

(deftest a-window-only-fires-on-its-own-weekday
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))
        ;; Tuesday 2026-03-10
        os (av/openings cal (at "2026-03-10T00:00:00Z") (at "2026-03-11T00:00:00Z")
                        [] (at "2026-03-09T00:00:00Z"))]
    (is (= [] (starts os)))))

(deftest partial-trailing-slot-is-not-offered
  ;; 10:00-11:20 with 30-minute slots is two slots, not two and a fragment.
  (let [cal (jst-cal (av/window :monday "10:00" "11:20"))
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [] (at "2026-03-08T00:00:00Z"))]
    (is (= ["2026-03-09T01:00:00Z" "2026-03-09T01:30:00Z"] (starts os)))))

;; ── G4 / G6: a taken slot is absent, and nothing marks that it was taken ──
(deftest a-confirmed-yoyaku-removes-exactly-its-own-slot
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))
        taken (confirmed* (at "2026-03-09T01:30:00Z") 30)
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [taken] (at "2026-03-08T00:00:00Z"))]
    (is (= ["2026-03-09T01:00:00Z" "2026-03-09T02:00:00Z" "2026-03-09T02:30:00Z"]
           (starts os)))
    (testing "absent, not annotated — no scarcity signal survives (G6)"
      (is (every? #(= #{:yotei/start-epoch-min :yotei/duration-min} (set (keys %))) os)))))

(deftest a-proposed-yoyaku-does-not-hold-the-slot
  ;; Otherwise anyone could freeze a calendar by proposing and never confirming.
  (let [cal (jst-cal (av/window :monday "10:00" "11:00"))
        proposed {"status" "proposed" "calendarDid" CAL
                  "startEpochMin" (at "2026-03-09T01:00:00Z") "durationMin" 30}
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [proposed] (at "2026-03-08T00:00:00Z"))]
    (is (= ["2026-03-09T01:00:00Z" "2026-03-09T01:30:00Z"] (starts os)))))

;; ── notice / horizon narrow the offer instead of refusing it later ──
(deftest notice-hides-slots-that-are-too-soon
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [] (at "2026-03-09T00:45:00Z"))]   ; +60min notice → 01:45
    (is (= ["2026-03-09T02:00:00Z" "2026-03-09T02:30:00Z"] (starts os)))))

(deftest horizon-hides-slots-too-far-out
  (let [cal (assoc (jst-cal (av/window :monday "10:00" "12:00")) :yotei/horizon-days 1)
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [] (at "2026-03-08T00:00:00Z"))]   ; horizon ends 03-09T00:00
    (is (= [] (starts os)))))

(deftest closed-dates-withdraw-every-window-that-day
  (let [cal (assoc (jst-cal (av/window :monday "10:00" "12:00"))
                   :yotei/closed-dates #{"2026-03-09"})
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-10T00:00:00Z")
                        [] (at "2026-03-08T00:00:00Z"))]
    (is (= [] (starts os)))))

(deftest an-empty-calendar-offers-nothing
  (is (= [] (av/openings (jst-cal) (at "2026-03-09T00:00:00Z")
                         (at "2026-03-16T00:00:00Z") [] (at "2026-03-08T00:00:00Z")))))

;; ── open? is the gate the 予約 route needs ──
(deftest open?-accepts-an-instant-that-was-offered
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))]
    (is (av/open? cal (at "2026-03-09T01:00:00Z") 30 [] (at "2026-03-08T00:00:00Z")))))

(deftest open?-refuses-an-instant-outside-every-window
  ;; The 3am-Sunday case: nothing has taken it, so no-double-book alone would
  ;; happily accept it.
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))]
    (is (not (av/open? cal (at "2026-03-08T03:00:00Z") 30 [] (at "2026-03-07T00:00:00Z"))))))

(deftest open?-refuses-a-slot-that-is-not-on-the-grid
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))]
    (is (not (av/open? cal (at "2026-03-09T01:15:00Z") 30 [] (at "2026-03-08T00:00:00Z"))))
    (testing "and refuses a duration the calendar does not offer"
      (is (not (av/open? cal (at "2026-03-09T01:00:00Z") 45 [] (at "2026-03-08T00:00:00Z")))))))

(deftest open?-refuses-a-taken-slot
  (let [cal (jst-cal (av/window :monday "10:00" "12:00"))
        taken (confirmed* (at "2026-03-09T01:00:00Z") 30)]
    (is (not (av/open? cal (at "2026-03-09T01:00:00Z") 30 [taken]
                       (at "2026-03-08T00:00:00Z"))))))

;; ── window validation ──
(deftest a-window-must-be-a-window
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (av/window :caturday "10:00" "12:00")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (av/window :monday "10:00" "09:00")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (av/window :monday "10:00" "10:00")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (av/window :monday "1000" "12:00"))))

(deftest by-local-day-groups-and-orders
  (let [cal (jst-cal (av/window :monday "10:00" "11:00")
                     (av/window :tuesday "10:00" "11:00"))
        os (av/openings cal (at "2026-03-09T00:00:00Z") (at "2026-03-11T00:00:00Z")
                        [] (at "2026-03-08T00:00:00Z"))
        grouped (av/by-local-day cal os)]
    (is (= ["2026-03-09" "2026-03-10"] (mapv :yotei/date grouped)))
    (is (every? #(= 2 (count (:yotei/openings %))) grouped))))
