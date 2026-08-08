(ns yotei.busy
  "The owner's existing appointments, as intervals and nothing else.

  Hand-written `:windows` say when the owner is *willing* to meet. They cannot
  say when the owner is *already busy*, because that changes every day — so a
  published calendar drifts out of truth immediately and starts offering slots
  that are already taken. This is the other half.

  ## Free/busy, not a copy of the calendar

  What crosses the boundary is `{:start :duration}`. No title, no attendees, no
  location, no organiser, no id from the source system. yotei needs to know
  *when* the owner is unavailable in order not to offer it, and needs nothing
  else — and the difference matters, because these intervals sit in KV at the
  edge while the 予約 contacts two namespaces over are encrypted precisely so
  they do not.

  A published busy interval still discloses something: that the owner is
  occupied then. That is unavoidable — it is the same thing an absent slot on
  the page discloses, and the page has to be able to omit the slot.

  ## A busy block occupies a slot exactly like a confirmed 予約

  So it is shaped like one. `yotei.availability/openings` already refuses any
  slot overlapping a confirmed entry; merging busy blocks into that list needs
  no change to the availability rules at all, and there is no second overlap
  test to disagree with the first.

  ## Floating times are read in the calendar's own offset

  An `.ics` from Google or iCloud usually carries `TZID` parameters that this
  parser does not interpret, leaving the datetime floating. Treating a floating
  value as UTC would shift every event by the owner's offset — nine hours in
  Tokyo — and silently free up mornings that are not free. So floating values
  are read in the calendar's `:yotei/tz-offset-min`, which is the owner's own
  clock and therefore what an untagged local time in their own calendar export
  almost certainly meant."
  (:require [yotei.time :as t]))

(defn- dt->epoch-min
  "An ical datetime map as epoch minutes.

  `:utc? true` is taken at face value. Anything else is local to
  `tz-offset-min` — see the namespace docstring for why that is safer than
  assuming UTC."
  [dt tz-offset-min]
  (when (and dt (:y dt) (:m dt) (:d dt))
    (let [local (+ (* (t/days-from-civil (:y dt) (:m dt) (:d dt)) 1440)
                   (* (get dt :hh 0) 60)
                   (get dt :mm 0))]
      (if (:utc? dt) local (- local tz-offset-min)))))

(defn from-ical
  "Busy intervals from a parsed iCalendar model.

  Events with no end are given `default-min`; an all-day event parses as a
  date with no time and becomes the whole day, which is the honest reading —
  somebody with an all-day entry is not necessarily free at 10am, and offering
  the slot is the worse error.

  Recurring events are **not expanded**. `RRULE` is parsed by the model but
  turning one into occurrences needs a full recurrence engine, and a wrong
  expansion would block slots that are actually free. Only the first
  occurrence blocks; the limitation is reported by `ingest-report` rather than
  hidden."
  [ical-model tz-offset-min default-min]
  (->> (:ical/events ical-model)
       (keep (fn [e]
               (let [s (dt->epoch-min (:ical/dtstart e) tz-offset-min)
                     end (dt->epoch-min (:ical/dtend e) tz-offset-min)
                     ;; `:date-only?` and not "hh and mm are both zero":
                     ;; the parser defaults both to 0 for a DATE *and* for
                     ;; midnight, so the arithmetic cannot tell them apart.
                     ;; The distinction is carried upstream instead.
                     all-day? (:date-only? (:ical/dtstart e))]
                 (when s
                   {:start s
                    :duration (cond
                                (and end (> end s)) (- end s)
                                all-day? 1440
                                :else default-min)}))))
       (sort-by :start)
       vec))

(defn merge-adjacent
  "Collapse overlapping and touching intervals.

  A calendar with a back-to-back morning produces a dozen intervals that are
  really one; collapsing them keeps the stored blob small and makes the
  overlap test cheaper. Touching counts as one — `[10:00,11:00)` and
  `[11:00,12:00)` leave no bookable gap between them."
  [intervals]
  (reduce (fn [acc {:keys [start duration] :as iv}]
            (if-let [prev (peek acc)]
              (if (<= start (+ (:start prev) (:duration prev)))
                (conj (pop acc)
                      (let [end (max (+ (:start prev) (:duration prev)) (+ start duration))]
                        {:start (:start prev) :duration (- end (:start prev))}))
                (conj acc iv))
              (conj acc iv)))
          []
          (sort-by :start intervals)))

(defn as-blocking
  "Busy intervals in the shape `yotei.availability` already refuses.

  Deliberately the same map a confirmed 予約 has, so there is one overlap rule
  in this codebase rather than two. `\"yoyakuId\"` is prefixed `busy-` so a
  reader of the merged list can tell where a block came from, and so the fold
  in `current-confirmed` keys them apart."
  [calendar-did intervals]
  (mapv (fn [{:keys [start duration]}]
          {"yoyakuId" (str "busy-" start "-" duration)
           "calendarDid" calendar-did
           "status" "confirmed"
           "startEpochMin" start
           "durationMin" duration
           "busy" true})
        intervals))

(defn window
  "The range worth ingesting: from now to the calendar's horizon.

  Past events cannot block a bookable slot — `openings` never offers one — and
  keeping them would grow the stored blob without bound."
  [now-epoch-min horizon-days]
  [now-epoch-min (+ now-epoch-min (* horizon-days 1440))])

(defn within
  "Only the intervals that overlap `[from, to)`."
  [intervals from to]
  (filterv (fn [{:keys [start duration]}]
             (and (< start to) (> (+ start duration) from)))
           intervals))

(defn ingest-report
  "What was taken in, and what was not — for the operator to read.

  Names the recurring events it did not expand. A count of 'events ingested'
  that quietly omits a weekly stand-up would read as success while leaving
  every Tuesday bookable."
  [ical-model intervals]
  (let [evts (:ical/events ical-model)
        recurring (count (filter :ical/rrule evts))]
    {:events (count evts)
     :intervals (count intervals)
     :recurring-not-expanded recurring}))
