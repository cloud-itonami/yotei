(ns yotei.ics-test
  "The .ics a visitor keeps.

  The assertion that matters is the `Z`. A floating DTSTART would put a 予約
  agreed for 10:00 in Tokyo at 10:00 wherever the attendee opens it, and
  nothing in the file would look wrong."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [yotei.availability :as av]
            [yotei.ics :as ics]
            [yotei.time :as t]))

(def cal
  (assoc (av/calendar "did:web:app.itonami.cloud:yotei:calendar:jun"
                      {:yotei/tz-offset-min 540 :yotei/slot-min 30
                       :yotei/windows [(av/window :monday "10:00" "12:00")]})
         :yotei/name "30分の打ち合わせ"
         :yotei/owner-label "川崎"))

(def entry
  {"yoyakuId" "y-abc" "status" "confirmed"
   "startEpochMin" (t/parse-instant "2026-03-09T01:00:00Z")
   "durationMin" 30})

(deftest times-are-utc-not-floating
  (let [s (ics/ics-str cal entry)]
    (is (str/includes? s "DTSTART:20260309T010000Z"))
    (is (str/includes? s "DTEND:20260309T013000Z"))
    (testing "and never a bare local-looking value"
      (is (not (str/includes? s "DTSTART:20260309T010000\r"))))))

(deftest it-is-a-parseable-calendar
  (let [s (ics/ics-str cal entry)]
    (is (str/starts-with? s "BEGIN:VCALENDAR"))
    (is (str/includes? s "END:VCALENDAR"))
    (is (str/includes? s "BEGIN:VEVENT"))
    (testing "CRLF line endings, as RFC 5545 requires"
      (is (str/includes? s "\r\n")))))

(deftest the-uid-is-the-yoyaku-so-re-downloading-updates-rather-than-duplicates
  (is (str/includes? (ics/ics-str cal entry) "UID:y-abc@app.itonami.cloud")))

(deftest it-carries-no-identity
  ;; An .ics gets forwarded, synced to third-party servers and backed up.
  ;; Identity stays in the envelope.
  (let [e (assoc entry "contactRef" "cloud.itonami.encrypted.v1:xxx")
        s (ics/ics-str cal e)]
    (doseq [leak ["ATTENDEE" "ORGANIZER" "@example.com" "encrypted.v1"]]
      (testing leak (is (not (str/includes? s leak)))))))

(deftest the-summary-says-what-and-with-whom
  (let [s (ics/ics-str cal entry)]
    (is (str/includes? s "30分の打ち合わせ"))
    (is (str/includes? s "川崎"))))
