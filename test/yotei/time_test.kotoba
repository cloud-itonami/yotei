(ns yotei.time-test
  "Anchors and round-trips for the civil-time arithmetic.

  The anchors matter more than they look. Every other test in this repo picks
  a weekday by calling `day-of-week`, so if the epoch were off by one the
  availability suite would still pass while publishing Tuesday's hours on
  Monday. Two independently-known dates pin it: 1970-01-01 was a Thursday, and
  2000-01-01 was a Saturday 10957 days later."
  (:require [clojure.test :refer [deftest is testing]]
            [yotei.time :as t]))

;; ── anchors ──
(deftest epoch-is-thursday
  (is (= 0 (t/days-from-civil 1970 1 1)))
  (is (= :thursday (t/day-of-week 0))))

(deftest millennium-anchor
  (is (= 10957 (t/days-from-civil 2000 1 1)))
  (is (= :saturday (t/day-of-week 10957))))

(deftest known-monday
  ;; Used by availability_test as its window day; pinned here so that suite
  ;; cannot silently drift onto another weekday.
  (is (= :monday (t/day-of-week (t/days-from-civil 2026 3 9)))))

;; ── civil round-trip ──
(deftest civil-round-trips
  (doseq [[y m d] [[1970 1 1] [2000 1 1] [2024 2 29] [2026 3 9]
                   [1900 3 1] [2100 3 1] [1969 12 31] [1900 1 1]]]
    (testing (str y "-" m "-" d)
      (is (= [y m d] (t/civil-from-days (t/days-from-civil y m d)))))))

(deftest handles-dates-before-the-epoch
  ;; 1969-12-31 is day -1; the floor-div in `epoch-day` is what keeps its
  ;; minutes on day -1 instead of truncating them onto day 0.
  (is (= -1 (t/days-from-civil 1969 12 31)))
  (is (= -1 (t/epoch-day -1)))
  (is (= "1969-12-31T23:59:00Z" (t/format-instant -1)))
  (is (= -1 (t/parse-instant "1969-12-31T23:59:00Z"))))

(deftest leap-day-is-a-day
  (is (= [2024 2 29] (t/civil-from-days (t/days-from-civil 2024 2 29)))))

;; ── parse-instant ──
(deftest parses-the-shapes-a-scheduler-receives
  (let [midnight (* 1440 (t/days-from-civil 2026 3 10))]
    (is (= midnight (t/parse-instant "2026-03-10")))
    (is (= (+ midnight 600) (t/parse-instant "2026-03-10T10:00")))
    (is (= (+ midnight 600) (t/parse-instant "2026-03-10T10:00:00Z")))
    (is (= (+ midnight 600) (t/parse-instant "  2026-03-10T10:00:00Z  ")))))

(deftest truncates-seconds-rather-than-rounding
  ;; Rounding 10:29:59 up to 10:30 would move a 予約 into the next slot.
  (is (= (t/parse-instant "2026-03-10T10:29")
         (t/parse-instant "2026-03-10T10:29:59Z"))))

(deftest refuses-a-non-utc-offset
  ;; Ignoring "+09:00" would book nine hours from where the caller meant.
  (is (nil? (t/parse-instant "2026-03-10T10:00:00+09:00")))
  (is (nil? (t/parse-instant "2026-03-10T10:00:00-05:00"))))

(deftest refuses-a-date-that-is-not-a-date
  (is (nil? (t/parse-instant "2026-02-30")))     ; would round-trip to Mar 2
  (is (nil? (t/parse-instant "2023-02-29")))     ; 2023 is not a leap year
  (is (nil? (t/parse-instant "2026-13-01")))
  (is (nil? (t/parse-instant "2026-03-10T24:00")))
  (is (nil? (t/parse-instant "2026-03-10T10:60")))
  (is (nil? (t/parse-instant "not-a-date")))
  (is (nil? (t/parse-instant "20xx-03-10")))
  (is (nil? (t/parse-instant "")))
  (is (nil? (t/parse-instant nil))))

(deftest instant-round-trips
  (doseq [s ["1970-01-01T00:00:00Z" "2026-03-10T10:00:00Z"
             "2024-02-29T23:59:00Z" "2100-01-01T00:00:00Z"]]
    (testing s
      (is (= s (t/format-instant (t/parse-instant s)))))))

;; ── hh:mm ──
(deftest parses-window-times
  (is (= 0 (t/parse-hhmm "00:00")))
  (is (= 570 (t/parse-hhmm "09:30")))
  (is (= 1439 (t/parse-hhmm "23:59")))
  (is (nil? (t/parse-hhmm "9:30")))              ; not zero-padded
  (is (nil? (t/parse-hhmm "24:00")))
  (is (nil? (t/parse-hhmm "09:60")))
  (is (nil? (t/parse-hhmm "0930")))
  (is (nil? (t/parse-hhmm nil))))

(deftest hhmm-round-trips
  (doseq [s ["00:00" "09:30" "13:05" "23:59"]]
    (is (= s (t/format-hhmm (t/parse-hhmm s))))))

(deftest day-start-is-midnight
  (let [noon (t/parse-instant "2026-03-10T12:00:00Z")]
    (is (= (t/parse-instant "2026-03-10T00:00:00Z") (t/day-start noon)))))
