(ns yotei.ics
  "A confirmed 予約 as a calendar file the visitor can keep.

  The visitor's half of the loop. They chose a time, were told honestly that it
  was not confirmed yet, and then — until now — nothing ever reached them
  again. yotei cannot email them: their contact is sealed to the owner's key
  and yotei holds no key, by design. What it *can* do is give them a page they
  can come back to, and a file their own calendar understands.

  ## Times are UTC, and that is the whole point

  `org-ietf-ical` emits RFC 5545 FORM #2 (`…Z`) when the datetime carries
  `:utc?`. Without it the value is FORM #1 — floating local time — and a
  meeting agreed for 10:00 in Tokyo would appear at 10:00 wherever the
  attendee happens to be. That flag was being parsed and discarded upstream
  until this repo needed it; the fix is in org-ietf-ical, not worked around
  here.

  ## What the file does not say

  No attendee, no organizer address, no contact. Those live in the envelope.
  An .ics is a file people forward, sync to third-party servers and back up —
  putting identity in it would undo the sealing for the sake of a nicety."
  (:require [ical.ical :as ical]
            [yotei.time :as t]))

(defn- ->dt
  "Epoch minutes as the ical model's datetime map, marked UTC."
  [epoch-min]
  (let [[y m d] (t/civil-from-days (t/epoch-day epoch-min))
        rem- (- epoch-min (* (t/epoch-day epoch-min) 1440))]
    {:y y :m m :d d :hh (quot rem- 60) :mm (mod rem- 60) :utc? true}))

(defn calendar-model
  "The iCalendar EDN model for one confirmed 予約."
  [cal entry]
  (let [start (get entry "startEpochMin")
        dur (get entry "durationMin")
        owner (or (:yotei/owner-label cal) "")
        title (or (not-empty (str (:yotei/name cal))) "予定")]
    {:ical/version "2.0"
     :ical/prodid "-//cloud-itonami//yotei//JA"
     :ical/events
     [{;; The 予約 id, so re-downloading the file updates the same entry in
       ;; the visitor's calendar rather than creating a second one.
       :ical/uid (str (get entry "yoyakuId") "@app.itonami.cloud")
       :ical/summary (if (seq owner) (str title "（" owner "）") title)
       :ical/description (str "yotei — " (:yotei/calendar-did cal))
       :ical/dtstart (->dt start)
       :ical/dtend (->dt (+ start dur))}]}))

(defn ics-str
  "The .ics text for one confirmed 予約."
  [cal entry]
  (ical/emit-str (calendar-model cal entry)))
