(ns yotei.busy-test
  "Ingesting the owner's existing appointments.

  The assertions worth having here are about *not* freeing up time that is
  taken. Every mistake in this namespace has the same shape — a busy interval
  read a few hours off, or dropped — and the symptom is a stranger booking
  over something real."
  (:require [clojure.test :refer [deftest is testing]]
            [ical.ical :as ical]
            [yotei.availability :as av]
            [yotei.busy :as busy]
            [yotei.time :as t]))

(def JST 540)
(def DID "did:web:app.itonami.cloud:yotei:calendar:jun")

(defn- ics [& events]
  (ical/parse-str
   (str "BEGIN:VCALENDAR\r\n"
        (apply str (map (fn [[uid s e]]
                          (str "BEGIN:VEVENT\r\nUID:" uid "\r\nDTSTART:" s "\r\n"
                               (when e (str "DTEND:" e "\r\n"))
                               "END:VEVENT\r\n"))
                        events))
        "END:VCALENDAR\r\n")))

;; ── reading times ──
(deftest utc-events-are-taken-at-face-value
  (let [ivs (busy/from-ical (ics ["a" "20260309T010000Z" "20260309T020000Z"]) JST 30)]
    (is (= [{:start (t/parse-instant "2026-03-09T01:00:00Z") :duration 60}] ivs))))

(deftest floating-events-are-read-in-the-owners-offset
  ;; The bug this prevents: an iCloud/Google export usually carries TZID, which
  ;; this parser leaves floating. Reading it as UTC would move a 10:00 Tokyo
  ;; meeting to 19:00 and hand out the morning.
  (let [ivs (busy/from-ical (ics ["a" "20260309T100000" "20260309T110000"]) JST 30)]
    (is (= [{:start (t/parse-instant "2026-03-09T01:00:00Z") :duration 60}] ivs))
    (testing "which is 10:00 on the owner's clock"
      (is (= "10:00" (subs (t/format-instant (+ (:start (first ivs)) JST)) 11 16))))))

(deftest an-all-day-event-blocks-the-whole-day
  ;; Somebody with an all-day entry is not reliably free at 10am, and offering
  ;; the slot is the worse error of the two.
  (let [ivs (busy/from-ical (ics ["a" "20260309"]) JST 30)]
    (is (= 1440 (:duration (first ivs))))))

(deftest an-event-with-no-end-gets-the-default
  (let [ivs (busy/from-ical (ics ["a" "20260309T010000Z"]) JST 45)]
    (is (= 45 (:duration (first ivs))))))

;; ── collapsing ──
(deftest overlapping-and-touching-intervals-collapse
  (is (= [{:start 100 :duration 120}]
         (busy/merge-adjacent [{:start 100 :duration 60} {:start 130 :duration 90}])))
  (testing "touching leaves no bookable gap, so it is one block"
    (is (= [{:start 100 :duration 120}]
           (busy/merge-adjacent [{:start 100 :duration 60} {:start 160 :duration 60}]))))
  (testing "a real gap stays two"
    (is (= 2 (count (busy/merge-adjacent [{:start 100 :duration 60}
                                          {:start 200 :duration 60}]))))))

(deftest a-contained-interval-does-not-shorten-the-one-around-it
  (is (= [{:start 100 :duration 180}]
         (busy/merge-adjacent [{:start 100 :duration 180} {:start 120 :duration 10}]))))

;; ── the point of all of it ──
(deftest a-busy-block-removes-the-slot-it-covers
  (let [cal (av/calendar DID {:yotei/tz-offset-min JST :yotei/slot-min 30
                              :yotei/windows [(av/window :monday "10:00" "12:00")]})
        now (t/parse-instant "2026-03-08T00:00:00Z")
        ;; 10:30-11:00 JST is busy
        blocking (busy/as-blocking DID [{:start (t/parse-instant "2026-03-09T01:30:00Z")
                                         :duration 30}])
        os (av/openings cal now (+ now (* 3 1440)) blocking now)]
    (is (= ["2026-03-09T01:00:00Z" "2026-03-09T02:00:00Z" "2026-03-09T02:30:00Z"]
           (mapv #(t/format-instant (:yotei/start-epoch-min %)) os)))))

(deftest busy-and-confirmed-block-through-one-rule
  ;; A busy block is shaped like a confirmed 予約 on purpose: there is one
  ;; overlap test in this codebase, not two that can drift apart.
  (let [cal (av/calendar DID {:yotei/tz-offset-min JST :yotei/slot-min 30
                              :yotei/windows [(av/window :monday "10:00" "12:00")]})
        now (t/parse-instant "2026-03-08T00:00:00Z")
        yoyaku {"yoyakuId" "y1" "status" "confirmed" "calendarDid" DID
                "startEpochMin" (t/parse-instant "2026-03-09T01:00:00Z") "durationMin" 30}
        blocking (busy/as-blocking DID [{:start (t/parse-instant "2026-03-09T02:00:00Z")
                                         :duration 30}])
        os (av/openings cal now (+ now (* 3 1440)) (into [yoyaku] blocking) now)]
    (is (= ["2026-03-09T01:30:00Z" "2026-03-09T02:30:00Z"]
           (mapv #(t/format-instant (:yotei/start-epoch-min %)) os)))))

(deftest blocking-entries-are-marked-so-they-can-be-told-apart
  (let [b (first (busy/as-blocking DID [{:start 100 :duration 30}]))]
    (is (true? (get b "busy")))
    (is (clojure.string/starts-with? (get b "yoyakuId") "busy-"))))

;; ── window / trimming ──
(deftest only-intervals-in-range-are-kept
  (let [ivs [{:start 100 :duration 30} {:start 5000 :duration 30}]]
    (is (= 1 (count (busy/within ivs 0 1000))))
    (testing "an interval straddling the boundary counts as inside"
      (is (= 1 (count (busy/within [{:start 90 :duration 30}] 100 1000)))))))

;; ── honesty about what was not done ──
(deftest recurring-events-are-reported-not-silently-dropped
  (let [model (ical/parse-str
               (str "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:r\r\n"
                    "DTSTART:20260309T010000Z\r\nDTEND:20260309T020000Z\r\n"
                    "RRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"))
        ivs (busy/from-ical model JST 30)
        report (busy/ingest-report model ivs)]
    (is (= 1 (:recurring-not-expanded report))
        "a weekly stand-up that was not expanded must be counted, not assumed handled")
    (is (= 1 (:intervals report)) "only its first occurrence blocks")))

(deftest an-empty-calendar-ingests-to-nothing
  (is (= [] (busy/from-ical (ical/parse-str "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n") JST 30))))

;; ── Google freeBusy ──────────────────────────────────────────────────────────
;;
;; freeBusy rather than events.list is a privacy decision with a test: the
;; response shape has nowhere to put a title, so there is nothing to discard
;; and nothing to trust us about.

(deftest google-freebusy-intervals
  (let [resp {"calendars"
              {"primary"
               {"busy" [{"start" "2026-03-09T10:00:00+09:00"
                         "end"   "2026-03-09T11:00:00+09:00"}
                        {"start" "2026-03-09T14:00:00+09:00"
                         "end"   "2026-03-09T14:30:00+09:00"}]}}}
        ivs (busy/from-google-freebusy resp)]
    (is (= 2 (count ivs)))
    (testing "the +09:00 offset is applied, not ignored"
      (is (= (t/parse-instant "2026-03-09T01:00:00Z") (:start (first ivs))))
      (is (= 60 (:duration (first ivs)))))
    (is (= 30 (:duration (second ivs))))))

(deftest google-freebusy-merges-several-calendars
  ;; A person with work and personal calendars is busy if either says so.
  (let [resp {"calendars"
              {"work" {"busy" [{"start" "2026-03-09T01:00:00Z" "end" "2026-03-09T02:00:00Z"}]}
               "home" {"busy" [{"start" "2026-03-09T05:00:00Z" "end" "2026-03-09T06:00:00Z"}]}}}
        ivs (busy/from-google-freebusy resp)]
    (is (= 2 (count ivs)))
    (testing "ordered by start regardless of which calendar they came from"
      (is (< (:start (first ivs)) (:start (second ivs)))))))

(deftest google-freebusy-tolerates-an-empty-or-errored-calendar
  (is (= [] (busy/from-google-freebusy {"calendars" {"primary" {"busy" []}}})))
  (is (= [] (busy/from-google-freebusy {})))
  (testing "an entry with errors and no busy list does not throw"
    (is (= [] (busy/from-google-freebusy
               {"calendars" {"x" {"errors" [{"domain" "global" "reason" "notFound"}]}}})))))

(deftest google-freebusy-drops-a-zero-or-negative-interval
  (is (= [] (busy/from-google-freebusy
             {"calendars" {"p" {"busy" [{"start" "2026-03-09T01:00:00Z"
                                         "end" "2026-03-09T01:00:00Z"}]}}}))))

(deftest rfc3339-offsets-round-the-right-way
  (is (= (t/parse-instant "2026-03-09T01:00:00Z") (t/parse-rfc3339 "2026-03-09T10:00:00+09:00")))
  (is (= (t/parse-instant "2026-03-09T15:00:00Z") (t/parse-rfc3339 "2026-03-09T10:00:00-05:00")))
  (testing "a plain Z still works"
    (is (= (t/parse-instant "2026-03-09T01:00:00Z") (t/parse-rfc3339 "2026-03-09T01:00:00Z"))))
  (testing "and parse-instant still refuses an offset, so nothing silently ignores one"
    (is (nil? (t/parse-instant "2026-03-09T10:00:00+09:00")))))
